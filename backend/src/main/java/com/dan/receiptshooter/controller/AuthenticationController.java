package com.dan.receiptshooter.controller;

import com.dan.receiptshooter.entity.UserAccount;
import com.dan.receiptshooter.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.security.Principal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
class AuthenticationController {
    private final AccountService accounts;
    AuthenticationController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping("/csrf")
    ResponseEntity<CsrfResponse> csrf(CsrfToken token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new CsrfResponse(token.getHeaderName(), token.getToken()));
    }

    @GetMapping("/session")
    ResponseEntity<SessionView> current(Principal principal) {
        var user = accounts.current(principal.getName());
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new SessionView(user.username(), user.role(), user.mustChangePassword()));
    }

    @PostMapping("/password")
    ResponseEntity<Void> changePassword(Principal principal, @Valid @RequestBody PasswordChange input,
                                        HttpServletRequest request) {
        accounts.changePassword(principal.getName(), input.currentPassword(), input.newPassword());
        invalidate(request);
        return ResponseEntity.noContent().build();
    }

    static void invalidate(HttpServletRequest request) {
        var session = request.getSession(false);
        if (session != null) session.invalidate();
        SecurityContextHolder.clearContext();
    }

    record CsrfResponse(String headerName, String token) {}
    record SessionView(String username, UserAccount.Role role, boolean mustChangePassword) {}
    record PasswordChange(@NotBlank String currentPassword, @NotBlank String newPassword) {}
}
