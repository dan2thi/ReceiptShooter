package com.dan.receiptshooter.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

// Only an EMPTY, dedicated disposable database may be used here.
@EnabledIfEnvironmentVariable(named = "MARIADB_SESSION_TEST_URL", matches = ".+")
class MariaDbSessionRestartTest {
    @Test
    void jdbcSessionSurvivesRestartOnMariaDb() throws Exception {
        String url = System.getenv("MARIADB_SESSION_TEST_URL");
        if (!url.matches("jdbc:mariadb://[^/?]+/[^/?]*_session_test(?:\\?.*)?")) {
            throw new IllegalStateException("MARIADB_SESSION_TEST_URL must name a separate database ending in _session_test.");
        }
        String user = System.getenv("MARIADB_TEST_USERNAME");
        String password = System.getenv("MARIADB_TEST_PASSWORD");
        if (user == null || password == null) throw new IllegalStateException("Set MariaDB test credentials.");
        SessionRestartIntegrationTest.verify(url, user, password, "org.mariadb.jdbc.Driver");
    }
}
