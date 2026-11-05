package com.dan.receiptshooter.repository;

import com.dan.receiptshooter.entity.ReceiptPhoto;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptPhotoRepository extends JpaRepository<ReceiptPhoto, Long> {
    List<ReceiptPhoto> findAllByReceiptIdOrderBySequenceAsc(Long receiptId);
    Optional<ReceiptPhoto> findByReceiptIdAndPhotoUuid(Long receiptId, String photoUuid);
}
