package com.dan.receiptshooter.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.Statement;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;

class DatabaseConfigurationTest {
    @Test
    void mariaDbUsesBoundedBinaryPreparedStatements() {
        var properties = new DataSourceProperties();
        properties.setUrl("jdbc:mariadb://localhost:3306/test");
        try (var pool = new DatabaseConfiguration().receiptDataSource(properties, 12L * 1024 * 1024)) {
            assertEquals("true", pool.getDataSourceProperties().get("useServerPrepStmts"));
            assertEquals("false", pool.getDataSourceProperties().get("cachePrepStmts"));
            assertEquals(Long.toString(257L * 1024 * 1024), pool.getDataSourceProperties().get("maxAllowedPacket"));
        }
    }

    @Test
    void databaseUrlsCannotContainCredentials() {
        var properties = new DataSourceProperties();
        properties.setUrl("jdbc:mariadb://localhost:3306/test?password=not-a-real-password");
        assertThrows(IllegalStateException.class, () -> new DatabaseConfiguration().receiptDataSource(properties, 4096));
        properties.setUrl("jdbc:mariadb://name:secret@localhost:3306/test");
        assertThrows(IllegalStateException.class, () -> new DatabaseConfiguration().receiptDataSource(properties, 4096));
    }

    @Test
    void packetCheckRejectsTooSmallMariaDbPackets() throws Exception {
        DataSource source = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        Statement statement = mock(Statement.class);
        ResultSet result = mock(ResultSet.class);
        ResultSet storedSizes = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("MariaDB");
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery("SELECT COALESCE(MAX(file_size), 0) FROM receipt_photos")).thenReturn(storedSizes);
        when(storedSizes.next()).thenReturn(true);
        when(storedSizes.getLong(1)).thenReturn(0L);
        when(statement.executeQuery("SELECT @@max_allowed_packet")).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getLong(1)).thenReturn(1024L);
        assertThrows(IllegalStateException.class, () -> new DatabasePacketLimitCheck(source, 4096).afterSingletonsInstantiated());

        when(result.getLong(1)).thenReturn(4096L + 1024 * 1024);
        assertDoesNotThrow(() -> new DatabasePacketLimitCheck(source, 4096).afterSingletonsInstantiated());

        when(storedSizes.getLong(1)).thenReturn(8192L);
        assertThrows(IllegalStateException.class, () -> new DatabasePacketLimitCheck(source, 4096).afterSingletonsInstantiated());
    }
}
