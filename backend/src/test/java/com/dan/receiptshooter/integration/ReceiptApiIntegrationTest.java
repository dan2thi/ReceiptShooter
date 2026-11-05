package com.dan.receiptshooter.integration;

import com.dan.receiptshooter.repository.PhotoBlobStore;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import com.dan.receiptshooter.entity.UserAccount;
import com.dan.receiptshooter.repository.UserAccountRepository;
import com.dan.receiptshooter.security.AccountPrincipal;

@SpringBootTest
@AutoConfigureMockMvc
class ReceiptApiIntegrationTest {
    private static final String TIME = "2026-01-02T03:04:05.000Z";

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @Autowired PasswordEncoder encoder;
    @MockitoSpyBean PhotoBlobStore blobs;

    record Part(UUID id, int sequence, byte[] bytes, String filename, String contentType) {}

    @BeforeEach
    void clearTestDatabase() {
        jdbc.update("DELETE FROM receipts");
        jdbc.update("UPDATE users SET must_change_password = FALSE WHERE username = ?", "test-user");
        if (users.findByUsername("other-user").isEmpty()) users.saveAndFlush(new UserAccount("other-user", encoder.encode("other-test-password"), UserAccount.Role.USER));
        jdbc.update("UPDATE users SET must_change_password = FALSE WHERE username = ?", "other-user");
        clearInvocations(blobs);
    }

    @Test
    void healthIsPublicAndReceiptEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/receipts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/receipts").header("Authorization", "Bearer incorrect")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/receipts/" + UUID.randomUUID())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/receipts/" + UUID.randomUUID() + "/photos/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/receipts").with(csrf())).andExpect(status().isUnauthorized());
    }

    @Test
    void singlePhotoUploadStoresAndReturnsTheOriginalBytes() throws Exception {
        UUID receipt = UUID.randomUUID();
        Part part = new Part(UUID.randomUUID(), 1, image("jpeg", 12, 12, 1), "C:\\fakepath\\receipt.jpg", "image/jpeg");
        MvcResult saved = mvc.perform(upload(receipt, List.of(part)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.receiptId").value(receipt.toString()))
                .andExpect(jsonPath("$.photoCount").value(1))
                .andExpect(jsonPath("$.status").value("STORED"))
                .andExpect(jsonPath("$.uploadedAt").isNotEmpty()).andReturn();

        mvc.perform(get("/api/receipts/{receipt}", receipt).with(asUser("test-user")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.receipt.uploaderId").value("test-user"))
                .andExpect(jsonPath("$.photos[0].originalFilename").value("receipt.jpg"))
                .andExpect(jsonPath("$.photos[0].contentType").value("image/jpeg"))
                .andExpect(jsonPath("$.photos[0].sha256").value(java.util.HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(part.bytes()))));
        byte[] returned = mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, part.id())
                        .with(asUser("test-user")).with(csrf()))
                .andExpect(status().isOk()).andExpect(content().contentType("image/jpeg"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(part.bytes(), returned);
        assertFalse(saved.getResponse().getContentAsString().contains("data:"));
    }

    @Test
    void multiplePhotosKeepTheirExactSequenceAndMetadataQueriesAvoidBlobs() throws Exception {
        UUID receipt = UUID.randomUUID();
        Part later = new Part(UUID.randomUUID(), 8, image("png", 10, 10, 2), "back.png", "image/png");
        Part earlier = new Part(UUID.randomUUID(), 3, image("jpeg", 10, 10, 3), "front.jpg", "image/jpeg");
        mvc.perform(upload(receipt, List.of(later, earlier))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.photoCount").value(2));
        clearInvocations(blobs);
        mvc.perform(get("/api/receipts").with(asUser("test-user")).with(csrf()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].photoCount").value(2));
        mvc.perform(get("/api/receipts/{receipt}", receipt).with(asUser("test-user")).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photos[0].photoId").value(earlier.id().toString()))
                .andExpect(jsonPath("$.photos[0].sequence").value(3))
                .andExpect(jsonPath("$.photos[1].photoId").value(later.id().toString()))
                .andExpect(jsonPath("$.photos[1].sequence").value(8));
        verify(blobs, never()).read(anyLong());
        assertArrayEquals(later.bytes(), mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, later.id())
                        .with(asUser("test-user")).with(csrf())).andExpect(content().contentType("image/png"))
                .andReturn().getResponse().getContentAsByteArray());
    }

    @Test
    void identicalRetriesReturnTheOriginalAcknowledgementAndNoDuplicates() throws Exception {
        UUID receipt = UUID.randomUUID();
        Part first = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 1), "one.png", "image/png");
        Part second = new Part(UUID.randomUUID(), 2, image("png", 10, 10, 2), "two.png", "image/png");
        String original = mvc.perform(upload(receipt, List.of(first, second))).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(upload(receipt, List.of(second, first))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertEquals(original, retry);
        assertEquals(1, count("receipts"));
        assertEquals(2, count("receipt_photos"));
        assertEquals(2, count("receipt_photo_data"));
    }

    @Test
    void changedBytesSequenceOrCaptureTimestampForTheSameReceiptCauseConflict() throws Exception {
        UUID receipt = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        Part original = new Part(id, 1, image("png", 10, 10, 1), "photo.png", "image/png");
        mvc.perform(upload(receipt, List.of(original))).andExpect(status().isCreated());
        Part changedBytes = new Part(id, 1, image("png", 10, 10, 2), "photo.png", "image/png");
        Part changedSequence = new Part(id, 2, original.bytes(), "photo.png", "image/png");
        mvc.perform(upload(receipt, List.of(changedBytes))).andExpect(status().isConflict());
        mvc.perform(upload(receipt, List.of(changedSequence))).andExpect(status().isConflict());
        mvc.perform(upload(receipt, List.of(original), "2026-02-02T03:04:05Z")).andExpect(status().isConflict());
        assertEquals(1, count("receipts"));
        assertEquals(1, count("receipt_photo_data"));
    }

    @Test
    void anIncompleteStoredReceiptIsNeverAcknowledgedOnRetry() throws Exception {
        UUID receipt = UUID.randomUUID();
        Part original = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 11), "photo.png", "image/png");
        mvc.perform(upload(receipt, List.of(original))).andExpect(status().isCreated());
        jdbc.update("DELETE FROM receipt_photo_data WHERE photo_id = (SELECT id FROM receipt_photos WHERE photo_uuid = ?)",
                original.id().toString());
        mvc.perform(upload(receipt, List.of(original))).andExpect(status().isInternalServerError());
        mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, original.id()).with(asUser("test-user")).with(csrf()))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void aPhotoUuidCannotBeReusedForADifferentReceipt() throws Exception {
        Part photo = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 12), "photo.png", "image/png");
        mvc.perform(upload(UUID.randomUUID(), List.of(photo))).andExpect(status().isCreated());
        mvc.perform(upload(UUID.randomUUID(), List.of(photo))).andExpect(status().isConflict());
        assertEquals(1, count("receipts"));
        assertEquals(1, count("receipt_photos"));
        assertEquals(1, count("receipt_photo_data"));
    }

    @Test
    void receiptIdentityIsScopedToTheAuthenticatedUploader() throws Exception {
        UUID receipt = UUID.randomUUID();
        Part one = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 1), "one.png", "image/png");
        Part two = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 2), "two.png", "image/png");
        mvc.perform(upload(receipt, List.of(one))).andExpect(status().isCreated());
        mvc.perform(uploadWithoutBearer(receipt, List.of(two), TIME).with(asUser("other-user")))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, one.id()).with(asUser("other-user")))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/receipts/{receipt}/photos/{photo}", receipt, two.id()).with(asUser("test-user")).with(csrf()))
                .andExpect(status().isNotFound());
        assertEquals(2, count("receipts"));
    }

    @Test
    void concurrentIdenticalUploadsCommitOnceAndBothReturnTheSameAcknowledgement() throws Exception {
        UUID receipt = UUID.randomUUID();
        Part photo = new Part(UUID.randomUUID(), 1, image("png", 12, 12, 4), "receipt.png", "image/png");
        CountDownLatch start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var calls = new ArrayList<java.util.concurrent.Future<MvcResult>>();
            for (int i = 0; i < 2; i++) {
                calls.add(workers.submit(() -> { start.await(); return mvc.perform(upload(receipt, List.of(photo))).andReturn(); }));
            }
            start.countDown();
            MvcResult first = calls.get(0).get(20, TimeUnit.SECONDS);
            MvcResult second = calls.get(1).get(20, TimeUnit.SECONDS);
            assertEquals(List.of(200, 201), java.util.stream.Stream.of(first, second)
                    .map(result -> result.getResponse().getStatus()).sorted().toList());
            assertEquals(first.getResponse().getContentAsString(), second.getResponse().getContentAsString());
        }
        assertEquals(1, count("receipts"));
        assertEquals(1, count("receipt_photos"));
        assertEquals(1, count("receipt_photo_data"));
    }

    @Test
    void concurrentMultipartRequestsAreBoundedEvenOnAnInvalidReceiptPath() throws Exception {
        CountDownLatch storing = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            storing.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out.");
            return invocation.callRealMethod();
        }).when(blobs).store(anyLong(), any(byte[].class));
        Part one = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 13), "one.png", "image/png");
        Part two = new Part(UUID.randomUUID(), 1, image("png", 10, 10, 14), "two.png", "image/png");
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> mvc.perform(upload(UUID.randomUUID(), List.of(one))
                    .servletPath("/api/receipts")).andReturn());
            var second = workers.submit(() -> mvc.perform(upload(UUID.randomUUID(), List.of(two))
                    .servletPath("/api/receipts")).andReturn());
            try {
                assertTrue(storing.await(10, TimeUnit.SECONDS));
                mvc.perform(multipart("/api/receipts/invalid").servletPath("/api/receipts/invalid")
                                .with(asUser("test-user")).with(csrf()))
                        .andExpect(status().isServiceUnavailable());
            } finally {
                release.countDown();
            }
            assertEquals(201, first.get(10, TimeUnit.SECONDS).getResponse().getStatus());
            assertEquals(201, second.get(10, TimeUnit.SECONDS).getResponse().getStatus());
        }
    }

    @Test
    void rejectsUnsupportedDamagedOrMislabelledImagesAndInvalidMetadata() throws Exception {
        byte[] png = image("png", 10, 10, 7);
        Part unsupported = new Part(UUID.randomUUID(), 1, "not an image".getBytes(StandardCharsets.UTF_8), "x.heic", "image/heic");
        Part corrupt = new Part(UUID.randomUUID(), 1, java.util.Arrays.copyOf(png, 16), "x.png", "image/png");
        Part mislabeled = new Part(UUID.randomUUID(), 1, png, "x.jpg", "image/jpeg");
        Part duplicateSequence = new Part(UUID.randomUUID(), 1, png, "y.png", "image/png");
        mvc.perform(upload(UUID.randomUUID(), List.of(unsupported))).andExpect(status().isBadRequest());
        mvc.perform(upload(UUID.randomUUID(), List.of(corrupt))).andExpect(status().isBadRequest());
        mvc.perform(upload(UUID.randomUUID(), List.of(mislabeled))).andExpect(status().isBadRequest());
        mvc.perform(upload(UUID.randomUUID(), List.of(mislabeled, duplicateSequence))).andExpect(status().isBadRequest());
        mvc.perform(upload(UUID.randomUUID(), List.of(new Part(UUID.randomUUID(), 1, png, "x.png", "image/png")), "2999-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        assertEquals(0, count("receipts"));
    }

    @Test
    void enforcesIndividualAndTotalPhotoLimits() throws Exception {
        Part tooLarge = new Part(UUID.randomUUID(), 1, new byte[4097], "large.jpg", "image/jpeg");
        mvc.perform(upload(UUID.randomUUID(), List.of(tooLarge))).andExpect(status().isPayloadTooLarge());

        byte[] noise = image("png", 30, 30, 999);
        assertTrue(noise.length < 4096 && noise.length * 3 > 8192, "fixture must exceed only the total limit");
        List<Part> many = List.of(
                new Part(UUID.randomUUID(), 1, noise, "one.png", "image/png"),
                new Part(UUID.randomUUID(), 2, noise, "two.png", "image/png"),
                new Part(UUID.randomUUID(), 3, noise, "three.png", "image/png"));
        mvc.perform(upload(UUID.randomUUID(), many)).andExpect(status().isPayloadTooLarge());
        BufferedImage panoramic = new BufferedImage(32769, 1, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(panoramic, "png", encoded));
        Part excessiveWidth = new Part(UUID.randomUUID(), 1, encoded.toByteArray(), "wide.png", "image/png");
        mvc.perform(upload(UUID.randomUUID(), List.of(excessiveWidth))).andExpect(status().isPayloadTooLarge());
        List<Part> tooMany = new ArrayList<>();
        for (int i = 1; i <= 9; i++) tooMany.add(new Part(UUID.randomUUID(), i, image("png", 2, 2, i), "x.png", "image/png"));
        mvc.perform(upload(UUID.randomUUID(), tooMany)).andExpect(status().isPayloadTooLarge());
        assertEquals(0, count("receipts"));
    }

    @Test
    void aDatabaseFailureDuringTheSecondBlobRollsBackTheWholeReceipt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) throw new DataAccessResourceFailureException("Simulated failure");
            return invocation.callRealMethod();
        }).when(blobs).store(anyLong(), any(byte[].class));
        List<Part> photos = List.of(
                new Part(UUID.randomUUID(), 1, image("png", 10, 10, 1), "one.png", "image/png"),
                new Part(UUID.randomUUID(), 2, image("png", 10, 10, 2), "two.png", "image/png"));
        mvc.perform(upload(UUID.randomUUID(), photos)).andExpect(status().isInternalServerError());
        assertEquals(0, count("receipts"));
        assertEquals(0, count("receipt_photos"));
        assertEquals(0, count("receipt_photo_data"));
    }

    @Test
    void changedPhotoBetweenValidationAndStorageRollsBackTheReceipt() throws Exception {
        UUID receipt = UUID.randomUUID();
        UUID photo = UUID.randomUUID();
        byte[] original = image("png", 10, 10, 17);
        String metadata = "{\"receiptId\":\"" + receipt + "\",\"capturedAt\":\"" + TIME +
                "\",\"photos\":[{\"id\":\"" + photo + "\",\"sequence\":1}]}";
        MockMultipartFile changed = new MockMultipartFile("photo-" + photo, "photo.png", "image/png", original) {
            @Override
            public byte[] getBytes() {
                return "changed contents".getBytes(StandardCharsets.UTF_8);
            }
        };
        mvc.perform(multipart("/api/receipts")
                        .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata.getBytes(StandardCharsets.UTF_8)))
                        .file(changed).with(asUser("test-user")).with(csrf()))
                .andExpect(status().isInternalServerError());
        assertEquals(0, count("receipts"));
        assertEquals(0, count("receipt_photos"));
        assertEquals(0, count("receipt_photo_data"));
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private RequestPostProcessor asUser(String username) {
        var principal = new AccountPrincipal(users.findByUsername(username).orElseThrow());
        return authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID id, List<Part> parts) {
        return upload(id, parts, TIME);
    }

    private MockMultipartHttpServletRequestBuilder upload(UUID id, List<Part> parts, String capturedAt) {
        return uploadWithoutBearer(id, parts, capturedAt).with(asUser("test-user")).with(csrf());
    }

    private MockMultipartHttpServletRequestBuilder uploadWithoutBearer(UUID id, List<Part> parts, String capturedAt) {
        String photoJson = parts.stream().map(part -> "{\"id\":\"" + part.id() + "\",\"sequence\":" + part.sequence() +
                ",\"contentType\":\"" + part.contentType() + "\",\"size\":" + part.bytes().length + "}")
                .collect(java.util.stream.Collectors.joining(","));
        String metadata = "{\"receiptId\":\"" + id + "\",\"capturedAt\":\"" + capturedAt + "\",\"photos\":[" + photoJson + "]}";
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/receipts")
                .file(new MockMultipartFile("metadata", "metadata.json", "application/json", metadata.getBytes(StandardCharsets.UTF_8)));
        for (Part part : parts) {
            builder.file(new MockMultipartFile("photo-" + part.id(), part.filename(), part.contentType(), part.bytes()));
        }
        return builder.with(csrf());
    }

    static byte[] image(String type, int width, int height, int seed) throws Exception {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(seed);
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) image.setRGB(x, y, random.nextInt(0x1000000));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, type, bytes));
        return bytes.toByteArray();
    }
}
