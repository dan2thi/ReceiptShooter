package com.dan.receiptshooter.integration;

import static org.junit.jupiter.api.Assertions.*;

import com.dan.receiptshooter.domain.AccountRole;
import com.dan.receiptshooter.repository.UserAccountRepository;
import com.dan.receiptshooter.service.AccountService;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.regex.Pattern;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:authentication;MODE=MariaDB;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "receipt.bootstrap.username=auth-admin",
        "receipt.bootstrap.password=initial-admin-password-123"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AuthenticationIntegrationTest {
    private static final String INITIAL = "initial-admin-password-123";
    private static final String CHANGED = "changed-admin-password-456";
    @LocalServerPort int port;
    @Autowired UserAccountRepository users;
    @Autowired AccountService accounts;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test @Order(1)
    void bootstrapHashesTheInitialPasswordAndNeverOverwritesAnExistingAdmin() {
        var admin = users.findByUsername("auth-admin").orElseThrow();
        assertEquals(AccountRole.ADMIN, admin.getRole());
        assertEquals("ADMIN", jdbc.queryForObject("SELECT role FROM users WHERE username = ?", String.class, "auth-admin"));
        assertTrue(admin.isEnabled());
        assertTrue(admin.isMustChangePassword());
        assertNotEquals(INITIAL, admin.getPasswordHash());
        assertTrue(encoder.matches(INITIAL, admin.getPasswordHash()));
        String oldHash = admin.getPasswordHash();
        accounts.bootstrap("another-admin", "different-initial-password");
        assertEquals(oldHash, users.findByUsername("auth-admin").orElseThrow().getPasswordHash());
        assertTrue(users.findByUsername("another-admin").isEmpty());
    }

    @Test @Order(2)
    void sessionsCsrfTemporaryPasswordsAdministrationAndRevocationWorkTogether() throws Exception {
        var anonymous = csrf(null);
        String cookieHeader = anonymous.setCookie();
        assertTrue(cookieHeader.contains("HttpOnly"));
        assertTrue(cookieHeader.contains("Secure"));
        assertTrue(cookieHeader.contains("SameSite=Lax"));
        assertTrue(cookieHeader.contains("Max-Age=2592000"));
        assertEquals(403, send("POST", "/api/auth/login", anonymous.cookie(), null, "application/x-www-form-urlencoded",
                form("auth-admin", INITIAL)).statusCode());
        assertEquals(401, send("POST", "/api/auth/login", anonymous.cookie(), anonymous.csrf(), "application/x-www-form-urlencoded",
                form("auth-admin", "incorrect-password")).statusCode());
        var login = send("POST", "/api/auth/login", anonymous.cookie(), anonymous.csrf(), "application/x-www-form-urlencoded", form("auth-admin", INITIAL));
        assertEquals(204, login.statusCode());
        String adminCookie = cookie(login);
        assertNotEquals(anonymous.cookie(), adminCookie, "Login must rotate the session ID.");
        assertTrue(send("GET", "/api/auth/session", adminCookie, null, null, null).body().contains("\"mustChangePassword\":true"));
        assertEquals(403, send("GET", "/api/receipts", adminCookie, null, null, null).statusCode());
        assertEquals(403, send("POST", "/api/receipts", adminCookie, null, "application/json", "{}").statusCode());

        var adminCsrf = csrf(adminCookie);
        assertEquals(204, send("POST", "/api/auth/password", adminCookie, adminCsrf.csrf(), "application/json",
                "{\"currentPassword\":\"" + INITIAL + "\",\"newPassword\":\"" + CHANGED + "\"}").statusCode());
        assertEquals(401, send("GET", "/api/auth/session", adminCookie, null, null, null).statusCode());
        adminCookie = login("auth-admin", CHANGED);
        adminCsrf = csrf(adminCookie);

        assertEquals(400, send("POST", "/api/auth/password", adminCookie, adminCsrf.csrf(), "application/json",
                "{\"currentPassword\":\"incorrect-password\",\"newPassword\":\"another-valid-password\"}").statusCode());
        assertEquals(400, send("POST", "/api/admin/users", adminCookie, adminCsrf.csrf(), "application/json",
                "{\"username\":\"invalid username\",\"temporaryPassword\":\"temporary-user-password\",\"role\":\"USER\"}").statusCode());
        assertEquals(404, send("PUT", "/api/admin/users/9223372036854775807/enabled", adminCookie, adminCsrf.csrf(),
                "application/json", "{\"enabled\":true}").statusCode());

        // A second repository instance can read the authenticated session from JDBC.
        String encodedId = adminCookie.substring(adminCookie.indexOf('=') + 1);
        String sessionId = new String(Base64.getDecoder().decode(encodedId), StandardCharsets.UTF_8);
        var secondRepository = new JdbcIndexedSessionRepository(jdbc, new TransactionTemplate(transactions));
        assertNotNull(secondRepository.findById(sessionId));
        assertEquals("auth-admin", jdbc.queryForObject("SELECT PRINCIPAL_NAME FROM SPRING_SESSION WHERE SESSION_ID = ?", String.class, sessionId));
        assertEquals(2592000, jdbc.queryForObject("SELECT MAX_INACTIVE_INTERVAL FROM SPRING_SESSION WHERE SESSION_ID = ?", Integer.class, sessionId));
        byte[] serialized = jdbc.queryForObject("SELECT a.ATTRIBUTE_BYTES FROM SPRING_SESSION_ATTRIBUTES a JOIN SPRING_SESSION s ON a.SESSION_PRIMARY_ID = s.PRIMARY_ID WHERE s.SESSION_ID = ? AND a.ATTRIBUTE_NAME = 'SPRING_SECURITY_CONTEXT'", byte[].class, sessionId);
        String serializedText = new String(serialized, StandardCharsets.ISO_8859_1);
        assertFalse(serializedText.contains(CHANGED));
        assertFalse(serializedText.contains(users.findByUsername("auth-admin").orElseThrow().getPasswordHash()));

        var created = send("POST", "/api/admin/users", adminCookie, adminCsrf.csrf(), "application/json",
                "{\"username\":\"family-user\",\"temporaryPassword\":\"temporary-user-password\",\"role\":\"USER\"}");
        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"role\":\"USER\""));
        assertFalse(created.body().contains("password"));
        var member = users.findByUsername("family-user").orElseThrow();
        assertEquals("USER", jdbc.queryForObject("SELECT role FROM users WHERE username = ?", String.class, "family-user"));
        assertTrue(member.isMustChangePassword());
        assertTrue(encoder.matches("temporary-user-password", member.getPasswordHash()));
        assertEquals(409, send("PUT", "/api/admin/users/" + users.findByUsername("auth-admin").orElseThrow().getId() + "/enabled",
                adminCookie, adminCsrf.csrf(), "application/json", "{\"enabled\":false}").statusCode());

        String memberCookie = login("family-user", "temporary-user-password");
        assertEquals(403, send("GET", "/api/admin/users", memberCookie, null, null, null).statusCode());
        var memberCsrf = csrf(memberCookie);
        assertEquals(204, send("POST", "/api/auth/password", memberCookie, memberCsrf.csrf(), "application/json",
                "{\"currentPassword\":\"temporary-user-password\",\"newPassword\":\"changed-user-password\"}").statusCode());
        memberCookie = login("family-user", "changed-user-password");
        assertEquals(403, send("GET", "/api/admin/users", memberCookie, null, null, null).statusCode());
        assertEquals(204, send("POST", "/api/admin/users/" + member.getId() + "/password", adminCookie, adminCsrf.csrf(), "application/json",
                "{\"temporaryPassword\":\"another-temporary-password\"}").statusCode());
        assertEquals(401, send("GET", "/api/auth/session", memberCookie, null, null, null).statusCode());
        memberCookie = login("family-user", "another-temporary-password");
        assertTrue(send("GET", "/api/auth/session", memberCookie, null, null, null).body().contains("\"mustChangePassword\":true"));
        assertEquals(200, send("PUT", "/api/admin/users/" + member.getId() + "/enabled", adminCookie, adminCsrf.csrf(),
                "application/json", "{\"enabled\":false}").statusCode());
        assertEquals(401, send("GET", "/api/auth/session", memberCookie, null, null, null).statusCode());
        var disabledAttempt = csrf(null);
        assertEquals(401, send("POST", "/api/auth/login", disabledAttempt.cookie(), disabledAttempt.csrf(),
                "application/x-www-form-urlencoded", form("family-user", "another-temporary-password")).statusCode());
        assertEquals(200, send("PUT", "/api/admin/users/" + member.getId() + "/enabled", adminCookie, adminCsrf.csrf(),
                "application/json", "{\"enabled\":true}").statusCode());
        assertNotNull(login("family-user", "another-temporary-password"));

        assertEquals(1, jdbc.update("UPDATE SPRING_SESSION SET LAST_ACCESS_TIME = 0, EXPIRY_TIME = 0 WHERE SESSION_ID = ?", sessionId));
        assertEquals(401, send("GET", "/api/auth/session", adminCookie, null, null, null).statusCode());
        adminCookie = login("auth-admin", CHANGED);
        adminCsrf = csrf(adminCookie);
        assertEquals(204, send("POST", "/api/auth/logout", adminCookie, adminCsrf.csrf(), null, "").statusCode());
        assertEquals(401, send("GET", "/api/auth/session", adminCookie, null, null, null).statusCode());
    }

    @Test @Order(3)
    void localRecoveryRequiresAnExistingAdminAndRevokesTheirSessions() throws Exception {
        String cookie = login("auth-admin", CHANGED);
        accounts.recoverAdministrator("auth-admin", "recovery-temporary-password");
        var admin = users.findByUsername("auth-admin").orElseThrow();
        assertTrue(admin.isMustChangePassword());
        assertTrue(encoder.matches("recovery-temporary-password", admin.getPasswordHash()));
        assertEquals(401, send("GET", "/api/auth/session", cookie, null, null, null).statusCode());
        assertNotNull(login("auth-admin", "recovery-temporary-password"));
        assertThrows(RuntimeException.class, () -> accounts.recoverAdministrator("family-user", "another-recovery-password"));
    }

    @Test @Order(4)
    void repeatedInvalidLoginsAreThrottled() throws Exception {
        var anonymous = csrf(null);
        for (int attempt = 0; attempt < 10; attempt++) {
            assertEquals(401, send("POST", "/api/auth/login", anonymous.cookie(), anonymous.csrf(),
                    "application/x-www-form-urlencoded", form("auth-admin", "invalid-password")).statusCode());
        }
        assertEquals(429, send("POST", "/api/auth/login", anonymous.cookie(), anonymous.csrf(),
                "application/x-www-form-urlencoded", form("auth-admin", "invalid-password")).statusCode());
    }

    @Test @Order(5)
    void concurrentDisablesCannotRemoveTheLastActiveAdministrator() throws Exception {
        var first = users.findByUsername("auth-admin").orElseThrow();
        var second = accounts.create("second-admin", "second-admin-temporary-password", AccountRole.ADMIN);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var a = workers.submit(() -> { start.await(); accounts.setEnabled(first.getId(), false); return true; });
            var b = workers.submit(() -> { start.await(); accounts.setEnabled(second.id(), false); return true; });
            start.countDown();
            int succeeded = 0;
            for (var task : java.util.List.of(a, b)) {
                try { if (task.get(15, java.util.concurrent.TimeUnit.SECONDS)) succeeded++; }
                catch (java.util.concurrent.ExecutionException expected) { /* The second disable must fail. */ }
            }
            assertEquals(1, succeeded);
            assertEquals(1, users.countByRoleAndEnabledTrue(AccountRole.ADMIN));
        }
    }

    private record Csrf(String cookie, String csrf, String setCookie) {}
    private Csrf csrf(String previousCookie) throws Exception {
        var result = send("GET", "/api/auth/csrf", previousCookie, null, null, null);
        assertEquals(200, result.statusCode());
        String header = result.headers().firstValue("Set-Cookie").orElse("");
        String cookie = header.isEmpty() ? previousCookie : header.split(";", 2)[0];
        var matcher = Pattern.compile("\"token\":\"([^\"]+)\"").matcher(result.body());
        assertTrue(matcher.find());
        return new Csrf(cookie, matcher.group(1), header);
    }

    private String login(String username, String password) throws Exception {
        var anonymous = csrf(null);
        var response = send("POST", "/api/auth/login", anonymous.cookie(), anonymous.csrf(),
                "application/x-www-form-urlencoded", form(username, password));
        assertEquals(204, response.statusCode());
        return cookie(response);
    }

    private static String cookie(HttpResponse<String> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }
    private static String form(String username, String password) {
        return "username=" + URLEncoder.encode(username, StandardCharsets.UTF_8) + "&password=" + URLEncoder.encode(password, StandardCharsets.UTF_8);
    }
    private HttpResponse<String> send(String method, String path, String cookie, String csrf, String type, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(10));
        if (cookie != null) builder.header("Cookie", cookie);
        if (csrf != null) builder.header("X-CSRF-TOKEN", csrf);
        if (type != null) builder.header("Content-Type", type);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }
}
