package com.dan.receiptshooter.service.impl;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.entity.Receipt;
import com.dan.receiptshooter.entity.ReceiptPhoto;
import com.dan.receiptshooter.repository.PhotoBlobStore;
import com.dan.receiptshooter.repository.ReceiptPhotoRepository;
import com.dan.receiptshooter.repository.ReceiptRepository;
import com.dan.receiptshooter.repository.UserAccountRepository;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class ReceiptWriter {
    private final ReceiptRepository receipts;
    private final ReceiptPhotoRepository photos;
    private final PhotoBlobStore blobs;
    private final UserAccountRepository users;

    ReceiptWriter(ReceiptRepository receipts, ReceiptPhotoRepository photos, PhotoBlobStore blobs, UserAccountRepository users) {
        this.receipts = receipts;
        this.photos = photos;
        this.blobs = blobs;
        this.users = users;
    }

    @Transactional
    public ReceiptApi.UploadAcknowledgement create(String uploader, ReceiptApi.UploadMetadata metadata, List<ValidatedPhoto> input) {
        var owner = users.findByUsername(uploader).filter(user -> user.isEnabled()).orElseThrow();
        Receipt receipt = receipts.saveAndFlush(new Receipt(metadata.receiptId().toString(),
                TimeValues.toDatabase(metadata.capturedAt()), TimeValues.toDatabase(Instant.now()), owner, input.size()));
        for (ValidatedPhoto photo : input) {
            ReceiptPhoto stored = photos.saveAndFlush(new ReceiptPhoto(receipt, photo.id(), photo.sequence(),
                    TimeValues.toDatabase(photo.capturedAt()), photo.originalFilename(), photo.contentType(),
                    photo.size(), photo.sha256()));
            blobs.store(stored.getId(), photo.verifiedBytes());
        }
        // The transactional proxy commits before returning this value to the caller.
        return ReceiptMapper.acknowledgement(receipt);
    }
}
