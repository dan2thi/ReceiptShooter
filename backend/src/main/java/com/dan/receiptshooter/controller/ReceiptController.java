package com.dan.receiptshooter.controller;

import com.dan.receiptshooter.dto.ReceiptApi;
import com.dan.receiptshooter.service.ReceiptQueryService;
import com.dan.receiptshooter.service.ReceiptUploadService;
import jakarta.validation.Valid;
import java.security.Principal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/receipts")
class ReceiptController {
    private final ReceiptUploadService uploads;
    private final ReceiptQueryService queries;

    ReceiptController(ReceiptUploadService uploads, ReceiptQueryService queries) {
        this.uploads = uploads;
        this.queries = queries;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ResponseEntity<ReceiptApi.UploadAcknowledgement> upload(Principal principal,
            @Valid @RequestPart("metadata") ReceiptApi.UploadMetadata metadata,
            MultipartHttpServletRequest request) {
        var result = uploads.upload(principal.getName(), metadata, request.getMultiFileMap());
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(result.acknowledgement());
    }

    @GetMapping
    ResponseEntity<List<ReceiptApi.ReceiptSummary>> list(Principal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.list(principal.getName()));
    }

    @GetMapping("/{receiptId}")
    ResponseEntity<ReceiptApi.ReceiptDetails> details(Principal principal, @PathVariable UUID receiptId) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(queries.details(principal.getName(), receiptId));
    }

    @GetMapping("/{receiptId}/photos/{photoId}")
    ResponseEntity<byte[]> image(Principal principal, @PathVariable UUID receiptId, @PathVariable UUID photoId) {
        var stored = queries.image(principal.getName(), receiptId, photoId);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(stored.contentType()))
                .cacheControl(CacheControl.noStore()).body(stored.data());
    }
}
