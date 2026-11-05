package com.dan.receiptshooter.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "receipt_photos")
public class ReceiptPhoto {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "receipt_id", nullable = false)
    private Receipt receipt;

    @Column(name = "photo_uuid", nullable = false, length = 36)
    private String photoUuid;

    @Column(name = "sequence_no", nullable = false)
    private int sequence;

    @Column(name = "captured_at")
    private LocalDateTime capturedAt;

    @Column(name = "original_filename", length = 255)
    private String originalFilename;

    @Column(name = "content_type", nullable = false, length = 64)
    private String contentType;

    @Column(name = "file_size", nullable = false)
    private long fileSize;

    @Column(name = "sha256", nullable = false, length = 64)
    private String sha256;

    protected ReceiptPhoto() {}

    public ReceiptPhoto(Receipt receipt, UUID photoId, int sequence, LocalDateTime capturedAt,
                        String originalFilename, String contentType, long fileSize, String sha256) {
        this.receipt = receipt;
        this.photoUuid = photoId.toString();
        this.sequence = sequence;
        this.capturedAt = capturedAt;
        this.originalFilename = originalFilename;
        this.contentType = contentType;
        this.fileSize = fileSize;
        this.sha256 = sha256;
    }

    public Long getId() { return id; }
    public String getPhotoUuid() { return photoUuid; }
    public int getSequence() { return sequence; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public String getOriginalFilename() { return originalFilename; }
    public String getContentType() { return contentType; }
    public long getFileSize() { return fileSize; }
    public String getSha256() { return sha256; }
}
