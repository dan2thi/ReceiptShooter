package com.dan.receiptshooter.service.impl;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.entity.Receipt;
import com.dan.receiptshooter.entity.ReceiptPhoto;
import com.dan.receiptshooter.exception.ReceiptConflictException;
import com.dan.receiptshooter.exception.ReceiptNotFoundException;
import com.dan.receiptshooter.repository.PhotoBlobStore;
import com.dan.receiptshooter.repository.ReceiptPhotoRepository;
import com.dan.receiptshooter.repository.ReceiptRepository;
import com.dan.receiptshooter.service.ReceiptQueryService;
import com.dan.receiptshooter.service.model.StoredImage;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReceiptQueryServiceImpl implements ReceiptQueryService, ExistingReceiptLookup {
    private final ReceiptRepository receipts;
    private final ReceiptPhotoRepository photos;
    private final PhotoBlobStore blobs;

    ReceiptQueryServiceImpl(ReceiptRepository receipts, ReceiptPhotoRepository photos, PhotoBlobStore blobs) {
        this.receipts = receipts;
        this.photos = photos;
        this.blobs = blobs;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ReceiptApi.UploadAcknowledgement> existing(String uploader, ReceiptApi.UploadMetadata request,
                                                               List<ValidatedPhoto> input) {
        var found = receipts.findByOwner_UsernameAndReceiptUuid(uploader, request.receiptId().toString());
        if (found.isEmpty()) return Optional.empty();
        Receipt receipt = found.get();
        List<ReceiptPhoto> stored = photos.findAllByReceiptIdOrderBySequenceAsc(receipt.getId());
        if (!receipt.getCapturedAt().equals(TimeValues.toDatabase(request.capturedAt())) ||
                receipt.getPhotoCount() != input.size() || stored.size() != input.size()) {
            throw new ReceiptConflictException();
        }
        if (blobs.countForReceipt(receipt.getId()) != stored.size()) {
            throw new IllegalStateException("Stored receipt data is incomplete.");
        }
        for (int i = 0; i < input.size(); i++) {
            ValidatedPhoto submitted = input.get(i);
            ReceiptPhoto saved = stored.get(i);
            if (!saved.getPhotoUuid().equals(submitted.id().toString()) ||
                    saved.getSequence() != submitted.sequence() ||
                    !Objects.equals(saved.getCapturedAt(), TimeValues.toDatabase(submitted.capturedAt())) ||
                    !Objects.equals(saved.getOriginalFilename(), submitted.originalFilename()) ||
                    !saved.getContentType().equals(submitted.contentType()) ||
                    saved.getFileSize() != submitted.size() || !saved.getSha256().equals(submitted.sha256())) {
                throw new ReceiptConflictException();
            }
        }
        return Optional.of(ReceiptMapper.acknowledgement(receipt));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ReceiptApi.ReceiptSummary> list(String uploader) {
        return receipts.findAllByOwner_UsernameOrderByCapturedAtDescIdDesc(uploader)
                .stream().map(ReceiptMapper::summary).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ReceiptApi.ReceiptDetails details(String uploader, UUID receiptId) {
        Receipt receipt = requireReceipt(uploader, receiptId);
        List<ReceiptApi.PhotoMetadata> photoMetadata = photos.findAllByReceiptIdOrderBySequenceAsc(receipt.getId())
                .stream().map(ReceiptMapper::photoMetadata).toList();
        return new ReceiptApi.ReceiptDetails(ReceiptMapper.summary(receipt), photoMetadata);
    }

    @Override
    @Transactional(readOnly = true)
    public StoredImage image(String uploader, UUID receiptId, UUID photoId) {
        Receipt receipt = requireReceipt(uploader, receiptId);
        ReceiptPhoto photo = photos.findByReceiptIdAndPhotoUuid(receipt.getId(), photoId.toString())
                .orElseThrow(ReceiptNotFoundException::new);
        byte[] data = blobs.read(photo.getId());
        if (data == null || data.length != photo.getFileSize() || !UploadValidator.sha256(data).equals(photo.getSha256())) {
            throw new IllegalStateException("Stored image is missing or damaged.");
        }
        return new StoredImage(photo.getContentType(), data);
    }

    private Receipt requireReceipt(String uploader, UUID id) {
        return receipts.findByOwner_UsernameAndReceiptUuid(uploader, id.toString()).orElseThrow(ReceiptNotFoundException::new);
    }
}
