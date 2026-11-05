package com.dan.receiptshooter.controller;

import com.dan.receiptshooter.entity.UserAccount;
import com.dan.receiptshooter.service.AccountService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.security.Principal;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
class UserManagementController {
    private final AccountService accounts;
    UserManagementController(AccountService accounts) { this.accounts = accounts; }

    @GetMapping
    ResponseEntity<List<AccountService.AccountView>> list() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(accounts.list());
    }

    @PostMapping
    ResponseEntity<AccountService.AccountView> create(@Valid @RequestBody NewAccount input) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore())
                .body(accounts.create(input.username(), input.temporaryPassword(), input.role()));
    }

    @PutMapping("/{id}/enabled")
    ResponseEntity<AccountService.AccountView> setEnabled(@PathVariable long id, @Valid @RequestBody Enabled input,
                                                           Principal principal, HttpServletRequest request) {
        var account = accounts.setEnabled(id, input.enabled());
        if (!account.enabled() && account.username().equals(principal.getName())) AuthenticationController.invalidate(request);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(account);
    }

    @PostMapping("/{id}/password")
    ResponseEntity<Void> resetPassword(@PathVariable long id, @Valid @RequestBody TemporaryPassword input,
                                       Principal principal, HttpServletRequest request) {
        var account = accounts.resetPassword(id, input.temporaryPassword());
        if (account.username().equals(principal.getName())) AuthenticationController.invalidate(request);
        return ResponseEntity.noContent().build();
    }

    record NewAccount(@NotBlank String username, @NotBlank String temporaryPassword, @NotNull UserAccount.Role role) {}
    record Enabled(@NotNull Boolean enabled) {}
    record TemporaryPassword(@NotBlank String temporaryPassword) {}
}
