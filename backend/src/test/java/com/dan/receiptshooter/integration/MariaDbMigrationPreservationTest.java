package com.dan.receiptshooter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// This test intentionally starts with an EMPTY, separate disposable database.
@EnabledIfEnvironmentVariable(named = "MARIADB_MIGRATION_TEST_URL", matches = ".+")
class MariaDbMigrationPreservationTest {
    @Test
    void migrationPreservesLegacyReceiptAndOriginalBlobOnMariaDb() throws Exception {
        String url = System.getenv("MARIADB_MIGRATION_TEST_URL");
        if (!url.matches("jdbc:mariadb://[^/?]+/[^/?]*_migration_test(?:\\?.*)?")) {
            throw new IllegalStateException("MARIADB_MIGRATION_TEST_URL must name a separate database ending in _migration_test.");
        }
        String user = System.getenv("MARIADB_TEST_USERNAME");
        String password = System.getenv("MARIADB_TEST_PASSWORD");
        if (user == null || password == null) throw new IllegalStateException("Set MariaDB test credentials.");
        MigrationPreservationTest.verify(url, user, password, "org.mariadb.jdbc.Driver");
    }
}
