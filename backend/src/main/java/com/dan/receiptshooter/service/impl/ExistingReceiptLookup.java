package com.dan.receiptshooter.service.impl;

import com.dan.receiptshooter.dto.ReceiptApi;
import java.util.List;
import java.util.Optional;

/** Internal consistency check shared by initial uploads and concurrent retries. */
interface ExistingReceiptLookup {
    Optional<ReceiptApi.UploadAcknowledgement> existing(String uploader, ReceiptApi.UploadMetadata request,
                                                        List<ValidatedPhoto> photos);
}
