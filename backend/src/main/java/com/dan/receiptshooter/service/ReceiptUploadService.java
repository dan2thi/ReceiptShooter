package com.dan.receiptshooter.service;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.service.model.UploadResult;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

/** Validates and stores a complete receipt, including idempotent retries. */
public interface ReceiptUploadService {
    UploadResult upload(String uploader, ReceiptApi.UploadMetadata metadata,
                        MultiValueMap<String, MultipartFile> files);
}
