package com.dan.receiptshooter.integration;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=1024",
        "spring.servlet.multipart.max-request-size=2097152"
})
class MultipartLimitHttpTest {
    @LocalServerPort int port;
    @Autowired JdbcTemplate jdbc;
    private final List<UUID> created = new ArrayList<>();

    @BeforeEach
    void permitTestAccount() {
        jdbc.update("UPDATE users SET must_change_password = FALSE WHERE username = ?", "test-user");
    }

    @AfterEach
    void cleanup() {
        for (UUID id : created) {
            jdbc.update("DELETE FROM receipts WHERE uploader_id = ? AND receipt_uuid = ?", "test-user", id.toString());
        }
    }

    @Test
    void embeddedServerReturnsPayloadTooLargeBeforeSavingAnOversizedMultipartFile() throws Exception {
        UUID receipt = UUID.randomUUID();
        UUID photo = UUID.randomUUID();
        String boundary = "receipt-test-boundary";
        String metadata = "{\"receiptId\":\"" + receipt + "\",\"capturedAt\":\"2026-01-02T03:04:05Z\",\"photos\":[{\"id\":\"" +
                photo + "\",\"sequence\":1}]}";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n" +
                "Content-Type: application/json\r\n\r\n" + metadata + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo-" + photo +
                "\"; filename=\"oversized.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(new byte[1500]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        var session = login();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/receipts"))
                .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(413, response.statusCode());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM receipts WHERE receipt_uuid = ?", Long.class, receipt.toString()));
    }

    @Test
    void realMultipartUploadAndRetrievalPreserveTheImage() throws Exception {
        UUID receipt = UUID.randomUUID();
        UUID photo = UUID.randomUUID();
        created.add(receipt);
        byte[] image = ReceiptApiIntegrationTest.image("png", 10, 10, 15);
        String boundary = "receipt-test-boundary";
        String metadata = "{\"receiptId\":\"" + receipt + "\",\"capturedAt\":\"2026-01-02T03:04:05Z\",\"photos\":[{\"id\":\"" +
                photo + "\",\"sequence\":1}]}";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\n" +
                "Content-Type: application/json\r\n\r\n" + metadata + "\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo-" + photo +
                "\"; filename=\"sample.png\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(image);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpClient client = HttpClient.newHttpClient();
        var session = login();
        HttpRequest upload = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/receipts"))
                .header("Cookie", session.cookie()).header("X-CSRF-TOKEN", session.csrf())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        HttpResponse<String> response = client.send(upload, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, response.statusCode());
        assertTrue(response.body().contains("\"status\":\"STORED\""));
        HttpRequest retrieve = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/receipts/" +
                        receipt + "/photos/" + photo))
                .header("Cookie", session.cookie()).GET().build();
        HttpResponse<byte[]> downloaded = client.send(retrieve, HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, downloaded.statusCode());
        assertEquals("image/png", downloaded.headers().firstValue("Content-Type").orElseThrow());
        assertArrayEquals(image, downloaded.body());
    }

    private record TestSession(String cookie, String csrf) {}

    private TestSession login() throws Exception {
        var client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        var initial = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
        String cookie = initial.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        String csrf = token(initial.body());
        var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                .header("Cookie", cookie).header("X-CSRF-TOKEN", csrf)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("username=test-user&password=initial-test-password-123")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(204, login.statusCode());
        cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
        var updated = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf"))
                .header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
        return new TestSession(cookie, token(updated.body()));
    }

    private static String token(String json) {
        var matcher = java.util.regex.Pattern.compile("\"token\":\"([^\"]+)\"").matcher(json);
        assertTrue(matcher.find());
        return matcher.group(1);
    }
}
