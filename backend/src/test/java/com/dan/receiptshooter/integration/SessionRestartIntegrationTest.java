package com.dan.receiptshooter.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.dan.receiptshooter.ReceiptShooterApplication;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

class SessionRestartIntegrationTest {
    @Test
    void authenticatedSessionSurvivesApplicationRestartWithoutBootstrapCredentials() throws Exception {
        String url = "jdbc:h2:mem:session-restart;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        verify(url, "sa", "", "org.h2.Driver");
    }

    static void verify(String url, String username, String password, String driver) throws Exception {
        String sessionCookie;
        HttpClient client = HttpClient.newHttpClient();
        try (ConfigurableApplicationContext first = start(url, username, password, driver, "--receipt.bootstrap.username=restart-admin",
                "--receipt.bootstrap.password=restart-initial-password")) {
            String base = base(first);
            var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/csrf")).GET().build(), HttpResponse.BodyHandlers.ofString());
            String initialCookie = cookie(csrf);
            var matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(csrf.body());
            assertTrue(matcher.find());
            var login = client.send(HttpRequest.newBuilder(URI.create(base + "/api/auth/login"))
                    .header("Cookie", initialCookie).header("X-CSRF-TOKEN", matcher.group(1))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString("username=restart-admin&password=restart-initial-password")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, login.statusCode());
            sessionCookie = cookie(login);
        }
        try (ConfigurableApplicationContext second = start(url, username, password, driver, "--receipt.bootstrap.username=", "--receipt.bootstrap.password=")) {
            var current = client.send(HttpRequest.newBuilder(URI.create(base(second) + "/api/auth/session"))
                    .header("Cookie", sessionCookie).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, current.statusCode());
            assertTrue(current.body().contains("\"username\":\"restart-admin\""));
        }
    }

    private static ConfigurableApplicationContext start(String url, String username, String password, String driver, String admin, String initialPassword) {
        return new SpringApplicationBuilder(ReceiptShooterApplication.class).run("--server.port=0",
                "--spring.datasource.url=" + url, "--spring.datasource.username=" + username, "--spring.datasource.password=" + password,
                "--spring.datasource.driver-class-name=" + driver, admin, initialPassword);
    }
    private static String base(ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((ServletWebServerApplicationContext) context).getWebServer().getPort();
    }
    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
}
