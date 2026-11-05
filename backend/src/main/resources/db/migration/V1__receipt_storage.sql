CREATE TABLE receipts (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_uuid VARCHAR(36) NOT NULL,
    captured_at DATETIME(6) NOT NULL,
    uploaded_at DATETIME(6) NOT NULL,
    uploader_id VARCHAR(128) NOT NULL,
    upload_status VARCHAR(16) NOT NULL,
    photo_count INT NOT NULL,
    CONSTRAINT uq_receipt_uploader_uuid UNIQUE (uploader_id, receipt_uuid),
    CONSTRAINT ck_receipt_photo_count CHECK (photo_count > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE receipt_photos (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    receipt_id BIGINT NOT NULL,
    photo_uuid VARCHAR(36) NOT NULL,
    sequence_no INT NOT NULL,
    captured_at DATETIME(6),
    original_filename VARCHAR(255),
    content_type VARCHAR(64) NOT NULL,
    file_size BIGINT NOT NULL,
    sha256 VARCHAR(64) NOT NULL,
    CONSTRAINT fk_photo_receipt FOREIGN KEY (receipt_id) REFERENCES receipts (id) ON DELETE CASCADE,
    CONSTRAINT uq_photo_uuid UNIQUE (photo_uuid),
    CONSTRAINT uq_photo_receipt_sequence UNIQUE (receipt_id, sequence_no),
    CONSTRAINT ck_photo_sequence CHECK (sequence_no > 0),
    CONSTRAINT ck_photo_size CHECK (file_size > 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE receipt_photo_data (
    photo_id BIGINT NOT NULL PRIMARY KEY,
    image_data LONGBLOB NOT NULL,
    CONSTRAINT fk_photo_data FOREIGN KEY (photo_id) REFERENCES receipt_photos (id) ON DELETE CASCADE
) ENGINE=InnoDB;
