package com.dan.receiptshooter.integration;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.head;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

import com.dan.receiptshooter.repository.UserAccountRepository;
import com.dan.receiptshooter.security.AccountPrincipal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
class FrontendDeliveryTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired UserAccountRepository users;
    @LocalServerPort int port;

    @Test
    void publicAppShellManifestWorkerAssetsAndNestedRoutesAreServed() throws Exception {
        assumeTrue(new ClassPathResource("static/index.html").exists(), "Build the frontend before testing packaged delivery.");
        // MockMvc records the welcome-page forward instead of dispatching it.
        mvc.perform(get("/")).andExpect(status().isOk()).andExpect(forwardedUrl("index.html"));
        mvc.perform(get("/index.html")).andExpect(status().isOk()).andExpect(content().string(containsString("id=\"root\"")));
        mvc.perform(get("/receipts/some/view")).andExpect(status().isOk()).andExpect(content().string(containsString("id=\"root\"")));
        mvc.perform(get("/manifest.webmanifest")).andExpect(status().isOk()).andExpect(content().string(containsString("start_url")));
        mvc.perform(head("/manifest.webmanifest")).andExpect(status().isOk());
        mvc.perform(get("/sw.js")).andExpect(status().isOk()).andExpect(content().string(containsString("SKIP_WAITING")));
        mvc.perform(get("/pwa-192x192.png")).andExpect(status().isOk());
        mvc.perform(get("/assets/missing.js")).andExpect(status().isNotFound());
        mvc.perform(get("/missing.png")).andExpect(status().isNotFound());
    }

    @Test
    void realHttpServesTheBuiltAppAndHealthOnTheSameOrigin() throws Exception {
        assumeTrue(new ClassPathResource("static/index.html").exists(), "Build the frontend before testing packaged delivery.");
        var client = HttpClient.newHttpClient();
        String base = "http://127.0.0.1:" + port;
        var root = client.send(HttpRequest.newBuilder(URI.create(base + "/")).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, root.statusCode());
        assertTrue(root.body().contains("id=\"root\""));
        assertTrue(root.body().contains("apple-mobile-web-app-capable"));
        var script = Pattern.compile("src=\"(/assets/[^\"]+\\.js)\"").matcher(root.body());
        assertTrue(script.find());
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + script.group(1))).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        var manifest = client.send(HttpRequest.newBuilder(URI.create(base + "/manifest.webmanifest")).GET().build(), HttpResponse.BodyHandlers.discarding());
        assertEquals(200, manifest.statusCode());
        assertTrue(manifest.headers().firstValue("Content-Type").orElse("").startsWith("application/manifest+json"));
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/sw.js")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200, client.send(HttpRequest.newBuilder(URI.create(base + "/api/health")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(401, client.send(HttpRequest.newBuilder(URI.create(base + "/api/receipts")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
    }

    @Test
    void apiAndUploadLimitsRemainProtectedAndAreNotRewrittenToTheApp() throws Exception {
        jdbc.update("UPDATE users SET must_change_password = FALSE WHERE username = ?", "test-user");
        var principal = new AccountPrincipal(users.findByUsername("test-user").orElseThrow());
        var authenticated = authentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, principal.getAuthorities()));
        mvc.perform(get("/api/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/api/receipts")).andExpect(status().isUnauthorized());
        mvc.perform(head("/api/receipts")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/receipts").with(csrf())).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/upload-limits")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/upload-limits").with(authenticated)).andExpect(status().isOk())
                .andExpect(jsonPath("$.maxPhotoBytes").value(4096))
                .andExpect(jsonPath("$.maxTotalBytes").value(8192))
                .andExpect(jsonPath("$.maxPhotos").value(8))
                .andExpect(jsonPath("$.maxImageEdge").value(32768));
        mvc.perform(get("/api/not-an-endpoint")).andExpect(status().isUnauthorized());
    }
}
