package com.dan.receiptshooter.service;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

record ValidatedPhoto(UUID id, int sequence, Instant capturedAt, String originalFilename,
                      String contentType, long size, String sha256, MultipartFile file) {
    byte[] verifiedBytes() {
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw new IllegalStateException("The validated image could not be read.");
        }
        if (bytes.length != size || !UploadValidator.sha256(bytes).equals(sha256)) {
            throw new IllegalStateException("The image changed while it was being stored.");
        }
        return bytes;
    }
}
