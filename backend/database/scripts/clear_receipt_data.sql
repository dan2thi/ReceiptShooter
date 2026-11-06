-- ReceiptShooter clean slate: preserves users and application metadata.
-- Run only after stopping all ReceiptShooter backends and taking a backup.

SET @previous_foreign_key_checks = @@SESSION.FOREIGN_KEY_CHECKS;
SET SESSION FOREIGN_KEY_CHECKS = 0;

-- Sign out all users.
TRUNCATE TABLE `receiptshooterdb`.`SPRING_SESSION_ATTRIBUTES`;
TRUNCATE TABLE `receiptshooterdb`.`SPRING_SESSION`;

TRUNCATE TABLE `receiptshooterdb`.`receipt_photo_data`;
TRUNCATE TABLE `receiptshooterdb`.`receipt_photos`;
TRUNCATE TABLE `receiptshooterdb`.`receipts`;


SET SESSION FOREIGN_KEY_CHECKS = @previous_foreign_key_checks;