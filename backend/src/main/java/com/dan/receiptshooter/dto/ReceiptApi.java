package com.dan.receiptshooter.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ReceiptApi {
    private ReceiptApi() {}

    public record UploadMetadata(@NotNull UUID receiptId, @NotNull Instant capturedAt,
                                 @NotEmpty List<@NotNull @Valid UploadPhoto> photos) {}

    public record UploadPhoto(@NotNull UUID id, @Positive int sequence, Instant capturedAt,
                              String contentType, Long size) {}

    public record UploadAcknowledgement(UUID receiptId, Instant uploadedAt, int photoCount, String status) {}

    public record ReceiptSummary(UUID receiptId, Instant capturedAt, Instant uploadedAt,
                                 String uploaderId, int photoCount, String status) {}

    public record PhotoMetadata(UUID photoId, int sequence, Instant capturedAt, String originalFilename,
                                String contentType, long size, String sha256) {}

    public record ReceiptDetails(ReceiptSummary receipt, List<PhotoMetadata> photos) {}

    public record ErrorResponse(String error) {}
}
