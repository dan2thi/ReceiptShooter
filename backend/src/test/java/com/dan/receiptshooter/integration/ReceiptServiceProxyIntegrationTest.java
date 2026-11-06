package com.dan.receiptshooter.integration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.service.ReceiptQueryService;
import com.dan.receiptshooter.service.ReceiptUploadService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@SpringBootTest(properties = {
        "spring.aop.proxy-target-class=false",
        "spring.datasource.url=jdbc:h2:mem:receipt-proxy;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
class ReceiptServiceProxyIntegrationTest {
    @Autowired ReceiptUploadService uploads;
    @Autowired ReceiptQueryService queries;

    @Test
    void interfaceProxiesPreserveUploadRetriesAndOriginalImage() throws Exception {
        UUID receiptId = UUID.randomUUID();
        UUID photoId = UUID.randomUUID();
        byte[] image = ReceiptApiIntegrationTest.image("png", 8, 8, 19);
        var metadata = new ReceiptApi.UploadMetadata(receiptId, Instant.now(), List.of(
                new ReceiptApi.UploadPhoto(photoId, 1, null, "image/png", (long) image.length)));
        var files = new LinkedMultiValueMap<String, MultipartFile>();
        files.add("photo-" + photoId, new MockMultipartFile("photo-" + photoId, "receipt.png", "image/png", image));

        var created = uploads.upload("test-user", metadata, files);
        var retry = uploads.upload("test-user", metadata, files);

        assertTrue(created.created());
        assertFalse(retry.created());
        assertEquals(created.acknowledgement(), retry.acknowledgement());
        assertEquals(photoId, queries.details("test-user", receiptId).photos().getFirst().photoId());
        assertArrayEquals(image, queries.image("test-user", receiptId, photoId).data());
    }
}
