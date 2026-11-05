package com.dan.receiptshooter.service;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.exception.ReceiptConflictException;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ReceiptUploads {
    private final UploadValidator validator;
    private final ReceiptWriter writer;
    private final ReceiptQueries queries;

    ReceiptUploads(UploadValidator validator, ReceiptWriter writer, ReceiptQueries queries) {
        this.validator = validator;
        this.writer = writer;
        this.queries = queries;
    }

    public UploadResult upload(String uploader, ReceiptApi.UploadMetadata request, MultiValueMap<String, MultipartFile> files) {
        List<ValidatedPhoto> input = validator.validate(request, files);
        var existing = queries.existing(uploader, request, input);
        if (existing.isPresent()) return new UploadResult(existing.get(), false);

        try {
            return new UploadResult(writer.create(uploader, request, input), true);
        } catch (DataIntegrityViolationException error) {
            // A concurrent request may have committed after our initial lookup.
            return new UploadResult(queries.existing(uploader, request, input).orElseThrow(ReceiptConflictException::new), false);
        }
    }

    public record UploadResult(ReceiptApi.UploadAcknowledgement acknowledgement, boolean created) {}
}
