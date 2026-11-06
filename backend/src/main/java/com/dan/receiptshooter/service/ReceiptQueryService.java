package com.dan.receiptshooter.service;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.service.model.StoredImage;
import java.util.List;
import java.util.UUID;

/** Reads receipts and original photographs belonging to one authenticated user. */
public interface ReceiptQueryService {
    List<ReceiptApi.ReceiptSummary> list(String uploader);

    ReceiptApi.ReceiptDetails details(String uploader, UUID receiptId);

    StoredImage image(String uploader, UUID receiptId, UUID photoId);
}
