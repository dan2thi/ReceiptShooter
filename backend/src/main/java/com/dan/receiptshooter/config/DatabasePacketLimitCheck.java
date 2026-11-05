package com.dan.receiptshooter.config;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
class DatabasePacketLimitCheck implements SmartInitializingSingleton {
    private final DataSource dataSource;
    private final long maxPhotoBytes;

    DatabasePacketLimitCheck(DataSource dataSource, @Value("${receipt.upload.max-photo-bytes}") long maxPhotoBytes) {
        this.dataSource = dataSource;
        this.maxPhotoBytes = maxPhotoBytes;
    }

    @Override
    public void afterSingletonsInstantiated() {
        try (Connection connection = dataSource.getConnection()) {
            if (!"MariaDB".equalsIgnoreCase(connection.getMetaData().getDatabaseProductName())) return;
            try (var statement = connection.createStatement()) {
                long storedMax;
                try (var result = statement.executeQuery("SELECT COALESCE(MAX(file_size), 0) FROM receipt_photos")) {
                    if (!result.next()) throw new IllegalStateException("Could not verify the largest stored photo.");
                    storedMax = result.getLong(1);
                }
                if (storedMax > 256L * 1024 * 1024) {
                    throw new IllegalStateException("A stored photo exceeds the supported maximum size.");
                }
                try (var result = statement.executeQuery("SELECT @@max_allowed_packet")) {
                    if (!result.next() || result.getLong(1) < Math.max(maxPhotoBytes, storedMax) + 1024 * 1024) {
                        throw new IllegalStateException("MariaDB max_allowed_packet must exceed the largest configured or stored photo by at least 1 MiB.");
                    }
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException("Could not verify the MariaDB packet size.");
        }
    }
}
