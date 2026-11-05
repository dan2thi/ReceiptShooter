package com.dan.receiptshooter.repository;

import com.dan.receiptshooter.entity.Receipt;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReceiptRepository extends JpaRepository<Receipt, Long> {
    Optional<Receipt> findByOwner_UsernameAndReceiptUuid(String username, String receiptUuid);
    List<Receipt> findAllByOwner_UsernameOrderByCapturedAtDescIdDesc(String username);
}
