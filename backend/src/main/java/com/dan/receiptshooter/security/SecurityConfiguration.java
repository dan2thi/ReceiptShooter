package com.dan.receiptshooter.security;

import com.dan.receiptshooter.repository.UserAccountRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.session.web.http.CookieSerializer;
import org.springframework.session.web.http.DefaultCookieSerializer;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    UserDetailsService userDetailsService(UserAccountRepository accounts) {
        return username -> accounts.findByUsername(username).map(AccountPrincipal::new)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials."));
    }

    @Bean
    CookieSerializer cookieSerializer(@Value("${receipt.session.seconds:2592000}") int seconds) {
        if (seconds < 60 || seconds > 90 * 24 * 60 * 60) {
            throw new IllegalStateException("SESSION_TIMEOUT_SECONDS must be between 60 and 7776000.");
        }
        var serializer = new DefaultCookieSerializer();
        serializer.setCookieName("RECEIPT_SESSION");
        serializer.setCookiePath("/");
        serializer.setUseHttpOnlyCookie(true);
        serializer.setUseSecureCookie(true);
        serializer.setSameSite("Lax");
        serializer.setCookieMaxAge(seconds);
        return serializer;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserAccountRepository accounts,
            @Value("${receipt.upload.max-concurrent:2}") int maxConcurrent) throws Exception {
        if (maxConcurrent < 1 || maxConcurrent > 8) {
            throw new IllegalStateException("MAX_CONCURRENT_UPLOADS must be between 1 and 8.");
        }
        var throttle = new LoginThrottleFilter();
        return http
                // Spring Security's session-backed CSRF token is exposed by /api/auth/csrf.
                .csrf(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.changeSessionId()))
                .requestCache(cache -> cache.disable())
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.loginProcessingUrl("/api/auth/login")
                        .successHandler((request, response, auth) -> { throttle.success(request); response.setStatus(HttpServletResponse.SC_NO_CONTENT); })
                        .failureHandler((request, response, error) -> { throttle.failure(request); response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); }))
                .logout(logout -> logout.logoutUrl("/api/auth/logout")
                        .invalidateHttpSession(true).clearAuthentication(true)
                        .logoutSuccessHandler((request, response, auth) -> response.setStatus(HttpServletResponse.SC_NO_CONTENT)))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/health", "/api/auth/csrf").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/api/health").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/auth/session", "/api/auth/password", "/api/auth/logout").authenticated()
                        .requestMatchers("/api/admin", "/api/admin/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/upload-limits").authenticated()
                        .requestMatchers("/api/receipts", "/api/receipts/**").authenticated()
                        .requestMatchers("/api", "/api/**").denyAll()
                        .requestMatchers(HttpMethod.GET, "/**").permitAll()
                        .requestMatchers(HttpMethod.HEAD, "/**").permitAll()
                        .anyRequest().denyAll())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, error) ->
                        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
                .headers(Customizer.withDefaults())
                .addFilterBefore(throttle, UsernamePasswordAuthenticationFilter.class)
                .addFilterAfter(new UploadConcurrencyFilter(maxConcurrent), UsernamePasswordAuthenticationFilter.class)
                .addFilterBefore(new AccountStateFilter(accounts), AuthorizationFilter.class)
                .build();
    }

    private static final class AccountStateFilter extends OncePerRequestFilter {
        private final UserAccountRepository accounts;
        AccountStateFilter(UserAccountRepository accounts) { this.accounts = accounts; }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            String path = request.getRequestURI().substring(request.getContextPath().length());
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            if (!path.startsWith("/api/") || authentication == null ||
                    !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
                chain.doFilter(request, response);
                return;
            }
            if (!(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
                reject(request, response);
                return;
            }
            var account = accounts.findByUsername(principal.getUsername()).orElse(null);
            if (account == null || !account.isEnabled() || !account.getId().equals(principal.getId()) ||
                    account.getAuthVersion() != principal.getAuthVersion()) {
                reject(request, response);
                return;
            }
            boolean passwordWorkflow = path.equals("/api/auth/session") || path.equals("/api/auth/csrf") ||
                    path.equals("/api/auth/password") || path.equals("/api/auth/logout") || path.equals("/api/health");
            if (account.isMustChangePassword() && !passwordWorkflow) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"PASSWORD_CHANGE_REQUIRED\"}");
                return;
            }
            chain.doFilter(request, response);
        }

        private void reject(HttpServletRequest request, HttpServletResponse response) {
            var session = request.getSession(false);
            if (session != null) session.invalidate();
            SecurityContextHolder.clearContext();
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    private static final class LoginThrottleFilter extends OncePerRequestFilter {
        private static final long WINDOW_MILLIS = 5 * 60 * 1000L;
        private static final int ATTEMPTS = 10;
        private final Map<String, Window> windows = new HashMap<>();

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            if (!request.getRequestURI().substring(request.getContextPath().length()).equals("/api/auth/login") || !request.getMethod().equals("POST")) {
                chain.doFilter(request, response);
                return;
            }
            long now = System.currentTimeMillis();
            String address = request.getRemoteAddr();
            synchronized (windows) {
                windows.entrySet().removeIf(entry -> now - entry.getValue().start > WINDOW_MILLIS);
                if (!windows.containsKey(address) && windows.size() >= 1024) {
                    response.setStatus(429);
                    return;
                }
                Window window = windows.get(address);
                if (window != null && window.count >= ATTEMPTS) {
                    response.setStatus(429);
                    response.setHeader("Retry-After", "300");
                    return;
                }
            }
            chain.doFilter(request, response);
        }
        void failure(HttpServletRequest request) {
            long now = System.currentTimeMillis();
            synchronized (windows) { windows.computeIfAbsent(request.getRemoteAddr(), key -> new Window(now)).count++; }
        }
        void success(HttpServletRequest request) { synchronized (windows) { windows.remove(request.getRemoteAddr()); } }
        private static final class Window {
            final long start;
            int count;
            Window(long start) { this.start = start; }
        }
    }

    private static final class UploadConcurrencyFilter extends OncePerRequestFilter {
        private final Semaphore permits;
        UploadConcurrencyFilter(int maxConcurrent) { permits = new Semaphore(maxConcurrent); }

        @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                throws ServletException, IOException {
            var authentication = SecurityContextHolder.getContext().getAuthentication();
            String path = request.getServletPath();
            String type = request.getContentType();
            boolean receiptPath = path.equals("/api/receipts") || path.startsWith("/api/receipts/");
            boolean multipart = type != null && type.toLowerCase(Locale.ROOT).startsWith("multipart/");
            if (!receiptPath || !multipart || authentication == null || !authentication.isAuthenticated() ||
                    authentication instanceof AnonymousAuthenticationToken) {
                chain.doFilter(request, response);
                return;
            }
            if (!permits.tryAcquire()) {
                response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
                response.setHeader("Retry-After", "5");
                response.setContentType("application/json");
                response.getWriter().write("{\"error\":\"Too many uploads are in progress; retry later.\"}");
                return;
            }
            try { chain.doFilter(request, response); } finally { permits.release(); }
        }
    }
}
