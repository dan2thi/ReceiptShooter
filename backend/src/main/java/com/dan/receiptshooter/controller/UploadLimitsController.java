package com.dan.receiptshooter.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class UploadLimitsController {
    private final UploadLimits limits;

    UploadLimitsController(@Value("${receipt.upload.max-photo-bytes}") long photoBytes,
                           @Value("${receipt.upload.max-total-bytes}") long totalBytes,
                           @Value("${receipt.upload.max-photos}") int photos,
                           @Value("${receipt.upload.max-pixels}") long pixels) {
        limits = new UploadLimits(photoBytes, totalBytes, photos, pixels, 32768);
    }

    @GetMapping("/api/upload-limits")
    ResponseEntity<UploadLimits> limits() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(limits);
    }

    record UploadLimits(long maxPhotoBytes, long maxTotalBytes, int maxPhotos,
                        long maxPixels, int maxImageEdge) {}
}
