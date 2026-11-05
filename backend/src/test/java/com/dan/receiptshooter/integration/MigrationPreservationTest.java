package com.dan.receiptshooter.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.dan.receiptshooter.ReceiptShooterApplication;
import java.sql.DriverManager;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

class MigrationPreservationTest {
    @Test
    void migrationAndFirstAdministratorPreserveLegacyReceiptAndPhotoBytes() throws Exception {
        String url = "jdbc:h2:mem:legacy-receipts;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000";
        verify(url, "sa", "", "org.h2.Driver");
    }

    static void verify(String url, String username, String password, String driver) throws Exception {
        Flyway.configure().dataSource(url, username, password).target("1").load().migrate();
        byte[] original = new byte[] {1, 2, 3, 0, -1, 42};
        try (var connection = DriverManager.getConnection(url, username, password); var statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO receipts (id, receipt_uuid, captured_at, uploaded_at, uploader_id, upload_status, photo_count) " +
                    "VALUES (100, '11111111-1111-4111-8111-111111111111', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'old-shared-user', 'STORED', 1)");
            statement.executeUpdate("INSERT INTO receipt_photos (id, receipt_id, photo_uuid, sequence_no, content_type, file_size, sha256) " +
                    "VALUES (200, 100, '22222222-2222-4222-8222-222222222222', 1, 'image/png', 6, 'test-hash')");
            try (var insert = connection.prepareStatement("INSERT INTO receipt_photo_data (photo_id, image_data) VALUES (200, ?)")) {
                insert.setBytes(1, original);
                insert.executeUpdate();
            }
        }
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(ReceiptShooterApplication.class)
                .run("--server.port=0", "--spring.datasource.url=" + url, "--spring.datasource.username=" + username,
                        "--spring.datasource.password=" + password, "--spring.datasource.driver-class-name=" + driver,
                        "--receipt.bootstrap.username=migration-admin", "--receipt.bootstrap.password=temporary-migration-password")) {
            JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
            assertEquals(1L, jdbc.queryForObject("SELECT COUNT(*) FROM receipts r JOIN users u ON r.user_id = u.id " +
                    "WHERE r.id = 100 AND u.username = 'migration-admin' AND r.uploader_id = 'old-shared-user'", Long.class));
            assertEquals("22222222-2222-4222-8222-222222222222", jdbc.queryForObject(
                    "SELECT photo_uuid FROM receipt_photos WHERE id = 200", String.class));
            assertArrayEquals(original, jdbc.queryForObject("SELECT image_data FROM receipt_photo_data WHERE photo_id = 200", byte[].class));
        }
    }
}
