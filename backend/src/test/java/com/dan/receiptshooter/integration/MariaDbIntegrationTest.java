package com.dan.receiptshooter.integration;

import com.dan.receiptshooter.repository.PhotoBlobStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.dan.receiptshooter.entity.UserAccount;
import com.dan.receiptshooter.repository.UserAccountRepository;
import com.dan.receiptshooter.security.AccountPrincipal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.junit.jupiter.api.extension.ExtendWith;

// Opt in only with a dedicated disposable MariaDB database.
@EnabledIfEnvironmentVariable(named = "MARIADB_TEST_URL", matches = ".+")
@SpringBootTest
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class MariaDbIntegrationTest {
    private final List<UUID> createdReceipts = new ArrayList<>();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean PhotoBlobStore blobs;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> requiredEnvironment("MARIADB_TEST_URL"));
        properties.add("spring.datasource.username", () -> requiredEnvironment("MARIADB_TEST_USERNAME"));
        properties.add("spring.datasource.password", () -> requiredEnvironment("MARIADB_TEST_PASSWORD"));
        properties.add("spring.datasource.driver-class-name", () -> "org.mariadb.jdbc.Driver");
        properties.add("receipt.upload.max-photo-bytes", () -> 12L * 1024 * 1024);
        properties.add("receipt.upload.max-total-bytes", () -> 48L * 1024 * 1024);
    }

    private static String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " must be set for the MariaDB test.");
        return value;
    }

    @AfterEach
    void cleanCreatedReceipts() {
        for (UUID id : createdReceipts) {
            jdbc.update("DELETE FROM receipts WHERE uploader_id = ? AND receipt_uuid = ?", "test-user", id.toString());
        }
    }

    @BeforeEach
    void prepareTestUser() {
        if (users.findByUsername("test-user").isEmpty()) users.saveAndFlush(new UserAccount("test-user", encoder.encode("mariadb-test-password"), UserAccount.Role.USER));
        jdbc.update("UPDATE users SET must_change_password = FALSE WHERE username = ?", "test-user");
    }

    @Test
    void migrationUsesInnoDbLongblobAndPreservesMultipleOriginalImages() throws Exception {
        assertEquals("longblob", jdbc.queryForObject(
                "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_photo_data' AND COLUMN_NAME = 'image_data'",
                String.class).toLowerCase());
        assertEquals("innodb", jdbc.queryForObject(
                "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'receipt_photo_data'",
                String.class).toLowerCase());
        UUID receipt = createId();
        UUID photo1 = UUID.randomUUID();
        UUID photo2 = UUID.randomUUID();
        byte[] first = ReceiptApiIntegrationTest.image("png", 10, 10, 3);
        byte[] second = ReceiptApiIntegrationTest.image("png", 10, 10, 4);
        String original = mvc.perform(upload(receipt, photo1, first, photo2, second))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(upload(receipt, photo1, first, photo2, second))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertEquals(original, retry);
        mvc.perform(get("/api/receipts/{id}", receipt).with(asUser()).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.photos[0].photoId").value(photo1.toString()))
                .andExpect(jsonPath("$.photos[1].photoId").value(photo2.toString()));
        assertArrayEquals(second, mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, photo2)
                .with(asUser()).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void aNearLimitPhotoRoundTripsWithTheDefaultUploadLimit() throws Exception {
        UUID receipt = createId();
        UUID photo = UUID.randomUUID();
        byte[] jpeg = ReceiptApiIntegrationTest.image("jpeg", 20, 20, 42);
        // JPEG permits trailing bytes; zeros also stress any text-protocol escaping.
        byte[] large = java.util.Arrays.copyOf(jpeg, 12 * 1024 * 1024 - 4096);
        String metadata = "{\"receiptId\":\"" + receipt + "\",\"capturedAt\":\"2026-01-02T03:04:05Z\",\"photos\":[{\"id\":\"" +
                photo + "\",\"sequence\":1}]}";
        mvc.perform(multipart("/api/receipts")
                        .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(new MockMultipartFile("photo-" + photo, "large.jpg", "image/jpeg", large))
                        .with(asUser()).with(csrf()))
                .andExpect(status().isCreated());
        assertArrayEquals(large, mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, photo)
                .with(asUser()).with(csrf())).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void concurrentDuplicateRequestsProduceOneCommittedReceipt(CapturedOutput output) throws Exception {
        UUID receipt = createId();
        UUID photo1 = UUID.randomUUID();
        UUID photo2 = UUID.randomUUID();
        byte[] first = ReceiptApiIntegrationTest.image("png", 10, 10, 5);
        byte[] second = ReceiptApiIntegrationTest.image("png", 10, 10, 6);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var one = workers.submit(() -> { start.await(); return mvc.perform(upload(receipt, photo1, first, photo2, second)).andReturn(); });
            var two = workers.submit(() -> { start.await(); return mvc.perform(upload(receipt, photo1, first, photo2, second)).andReturn(); });
            start.countDown();
            MvcResult a = one.get(30, TimeUnit.SECONDS);
            MvcResult b = two.get(30, TimeUnit.SECONDS);
            assertEquals(List.of(200, 201), java.util.stream.Stream.of(a, b)
                    .map(result -> result.getResponse().getStatus()).sorted().toList());
            assertEquals(a.getResponse().getContentAsString(), b.getResponse().getContentAsString());
        }
        assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM receipts WHERE uploader_id = ? AND receipt_uuid = ?",
                Long.class, "test-user", receipt.toString()));
        assertFalse(output.getAll().contains(receipt.toString()), "Receipt IDs must not appear in logs.");
    }

    @Test
    void aBlobFailureRollsBackTheReceiptAndEveryPhoto() throws Exception {
        UUID receipt = createId();
        UUID photo1 = UUID.randomUUID();
        UUID photo2 = UUID.randomUUID();
        AtomicInteger writes = new AtomicInteger();
        doAnswer(invocation -> {
            if (writes.incrementAndGet() == 2) throw new DataAccessResourceFailureException("Simulated failure");
            return invocation.callRealMethod();
        }).when(blobs).store(anyLong(), any(byte[].class));
        mvc.perform(upload(receipt, photo1, ReceiptApiIntegrationTest.image("png", 10, 10, 7),
                photo2, ReceiptApiIntegrationTest.image("png", 10, 10, 8))).andExpect(status().isInternalServerError());
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM receipts WHERE uploader_id = ? AND receipt_uuid = ?",
                Long.class, "test-user", receipt.toString()));
        assertEquals(0L, jdbc.queryForObject("SELECT COUNT(*) FROM receipt_photos WHERE photo_uuid IN (?, ?)",
                Long.class, photo1.toString(), photo2.toString()));
    }

    private UUID createId() {
        UUID id = UUID.randomUUID();
        createdReceipts.add(id);
        return id;
    }

    private RequestPostProcessor asUser() {
        var principal = new AccountPrincipal(users.findByUsername("test-user").orElseThrow());
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID receipt, UUID photo1, byte[] first, UUID photo2, byte[] second) {
        String metadata = "{\"receiptId\":\"" + receipt + "\",\"capturedAt\":\"2026-01-02T03:04:05Z\",\"photos\":[" +
                "{\"id\":\"" + photo1 + "\",\"sequence\":1},{\"id\":\"" + photo2 + "\",\"sequence\":2}]}";
        return multipart("/api/receipts")
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata.getBytes(StandardCharsets.UTF_8)))
                .file(new MockMultipartFile("photo-" + photo1, "one.png", "image/png", first))
                .file(new MockMultipartFile("photo-" + photo2, "two.png", "image/png", second))
                .with(asUser()).with(csrf());
    }
}
