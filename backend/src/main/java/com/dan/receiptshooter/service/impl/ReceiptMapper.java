package com.dan.receiptshooter.service.impl;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.entity.Receipt;
import com.dan.receiptshooter.entity.ReceiptPhoto;
import java.util.UUID;

/** Maps persistence entities to the existing receipt API representations. */
final class ReceiptMapper {
    private ReceiptMapper() {}

    static ReceiptApi.UploadAcknowledgement acknowledgement(Receipt receipt) {
        return new ReceiptApi.UploadAcknowledgement(UUID.fromString(receipt.getReceiptUuid()),
                TimeValues.fromDatabase(receipt.getUploadedAt()), receipt.getPhotoCount(), receipt.getUploadStatus());
    }

    static ReceiptApi.ReceiptSummary summary(Receipt receipt) {
        return new ReceiptApi.ReceiptSummary(UUID.fromString(receipt.getReceiptUuid()),
                TimeValues.fromDatabase(receipt.getCapturedAt()), TimeValues.fromDatabase(receipt.getUploadedAt()),
                receipt.getOwner().getUsername(), receipt.getPhotoCount(), receipt.getUploadStatus());
    }

    static ReceiptApi.PhotoMetadata photoMetadata(ReceiptPhoto photo) {
        return new ReceiptApi.PhotoMetadata(UUID.fromString(photo.getPhotoUuid()), photo.getSequence(),
                TimeValues.fromDatabase(photo.getCapturedAt()), photo.getOriginalFilename(), photo.getContentType(),
                photo.getFileSize(), photo.getSha256());
    }
}
