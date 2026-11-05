package com.dan.receiptshooter.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "receipts")
public class Receipt {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "receipt_uuid", nullable = false, length = 36)
    private String receiptUuid;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "uploaded_at", nullable = false)
    private LocalDateTime uploadedAt;

    @Column(name = "uploader_id", nullable = false, length = 128)
    private String uploaderId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private UserAccount owner;

    @Column(name = "upload_status", nullable = false, length = 16)
    private String uploadStatus;

    @Column(name = "photo_count", nullable = false)
    private int photoCount;

    @OneToMany(mappedBy = "receipt", fetch = FetchType.LAZY)
    private List<ReceiptPhoto> photos = new ArrayList<>();

    protected Receipt() {}

    public Receipt(String receiptUuid, LocalDateTime capturedAt, LocalDateTime uploadedAt, UserAccount owner, int photoCount) {
        this.receiptUuid = receiptUuid;
        this.capturedAt = capturedAt;
        this.uploadedAt = uploadedAt;
        this.owner = owner;
        this.uploaderId = owner.getUsername();
        this.photoCount = photoCount;
        this.uploadStatus = "STORED";
    }

    public Long getId() { return id; }
    public String getReceiptUuid() { return receiptUuid; }
    public LocalDateTime getCapturedAt() { return capturedAt; }
    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public String getUploaderId() { return uploaderId; }
    public UserAccount getOwner() { return owner; }
    public String getUploadStatus() { return uploadStatus; }
    public int getPhotoCount() { return photoCount; }
}
