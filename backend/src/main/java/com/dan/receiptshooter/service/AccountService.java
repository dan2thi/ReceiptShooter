package com.dan.receiptshooter.service;

import com.dan.receiptshooter.entity.UserAccount;
import com.dan.receiptshooter.exception.AccountException;
import com.dan.receiptshooter.repository.UserAccountRepository;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {
    private final UserAccountRepository accounts;
    private final PasswordEncoder passwords;
    private final JdbcTemplate jdbc;
    private final FindByIndexNameSessionRepository<? extends Session> sessions;

    public AccountService(UserAccountRepository accounts, PasswordEncoder passwords, JdbcTemplate jdbc,
                          FindByIndexNameSessionRepository<? extends Session> sessions) {
        this.accounts = accounts; this.passwords = passwords; this.jdbc = jdbc; this.sessions = sessions;
    }

    @Transactional
    public void bootstrap(String username, String initialPassword) {
        jdbc.queryForObject("SELECT id FROM account_bootstrap_lock WHERE id = 1 FOR UPDATE", Integer.class);
        if (accounts.countByRole(UserAccount.Role.ADMIN) != 0) return;
        if (username == null || username.isBlank() || initialPassword == null || initialPassword.isEmpty()) {
            throw new IllegalStateException("Set ADMIN_USERNAME and ADMIN_INITIAL_PASSWORD for the first startup.");
        }
        validateUsername(username);
        validatePassword(initialPassword);
        if (accounts.findByUsername(username).isPresent()) throw new IllegalStateException("The bootstrap username is already in use.");
        var admin = accounts.saveAndFlush(new UserAccount(username, passwords.encode(initialPassword), UserAccount.Role.ADMIN));
        // Milestone 2B used one shared uploader identity. Claim its existing
        // receipt rows without touching their photo metadata or BLOBs.
        jdbc.update("UPDATE receipts SET user_id = ? WHERE user_id IS NULL", admin.getId());
    }

    @Transactional(readOnly = true)
    public List<AccountView> list() {
        return accounts.findAll(Sort.by("username")).stream().map(AccountService::view).toList();
    }

    @Transactional(readOnly = true)
    public AccountView current(String username) { return view(requireUsername(username)); }

    @Transactional
    public AccountView create(String username, String temporaryPassword, UserAccount.Role role) {
        validateUsername(username); validatePassword(temporaryPassword);
        if (role == null) throw badRequest("Choose an account role.");
        if (accounts.findByUsername(username).isPresent()) throw conflict("That username is already in use.");
        try { return view(accounts.saveAndFlush(new UserAccount(username, passwords.encode(temporaryPassword), role))); }
        catch (DataIntegrityViolationException error) { throw conflict("That username is already in use."); }
    }

    @Transactional
    public AccountView setEnabled(long id, boolean enabled) {
        List<UserAccount> administrators = accounts.lockActiveAdministrators(UserAccount.Role.ADMIN);
        UserAccount account = requireId(id);
        if (!enabled && account.isEnabled() && account.getRole() == UserAccount.Role.ADMIN && administrators.size() <= 1) {
            throw conflict("The last active administrator cannot be disabled.");
        }
        boolean changed = account.isEnabled() != enabled;
        account.setEnabled(enabled);
        if (changed) { accounts.saveAndFlush(account); revoke(account.getUsername()); }
        return view(account);
    }

    @Transactional
    public AccountView resetPassword(long id, String temporaryPassword) {
        validatePassword(temporaryPassword);
        UserAccount account = requireId(id);
        account.setPassword(passwords.encode(temporaryPassword), true);
        accounts.saveAndFlush(account);
        revoke(account.getUsername());
        return view(account);
    }

    @Transactional
    public void changePassword(String username, String currentPassword, String newPassword) {
        validatePassword(newPassword);
        UserAccount account = requireUsername(username);
        if (currentPassword == null || !passwords.matches(currentPassword, account.getPasswordHash())) {
            throw new AccountException(HttpStatus.BAD_REQUEST, "Current password is incorrect.");
        }
        if (passwords.matches(newPassword, account.getPasswordHash())) throw badRequest("Choose a different password.");
        account.setPassword(passwords.encode(newPassword), false);
        accounts.saveAndFlush(account);
        revoke(account.getUsername());
    }

    @Transactional
    public void recoverAdministrator(String username, String temporaryPassword) {
        validatePassword(temporaryPassword);
        UserAccount account = requireUsername(username);
        if (account.getRole() != UserAccount.Role.ADMIN) throw badRequest("Recovery is available only for an existing administrator.");
        account.setPassword(passwords.encode(temporaryPassword), true);
        account.setEnabled(true);
        accounts.saveAndFlush(account);
        revoke(account.getUsername());
    }

    private void revoke(String username) {
        sessions.findByPrincipalName(username).keySet().forEach(sessions::deleteById);
    }
    private UserAccount requireId(long id) {
        return accounts.findById(id).orElseThrow(() -> new AccountException(HttpStatus.NOT_FOUND, "Account not found."));
    }
    private UserAccount requireUsername(String username) {
        return accounts.findByUsername(username).orElseThrow(() -> new AccountException(HttpStatus.UNAUTHORIZED, "Sign in again."));
    }
    private static void validateUsername(String username) {
        if (username == null || !username.matches("[a-z0-9][a-z0-9._@-]{0,63}")) {
            throw badRequest("Use 1–64 lowercase letters, digits, dots, underscores, @ or hyphens; start with a letter or digit.");
        }
    }
    private static void validatePassword(String password) {
        if (password == null || password.length() < 12 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw badRequest("Passwords must be at least 12 characters and at most 72 UTF-8 bytes.");
        }
    }
    private static AccountException badRequest(String message) { return new AccountException(HttpStatus.BAD_REQUEST, message); }
    private static AccountException conflict(String message) { return new AccountException(HttpStatus.CONFLICT, message); }
    private static AccountView view(UserAccount user) {
        return new AccountView(user.getId(), user.getUsername(), user.getRole(), user.isEnabled(),
                user.isMustChangePassword(), user.getCreatedAt(), user.getUpdatedAt());
    }
    public record AccountView(Long id, String username, UserAccount.Role role, boolean enabled,
                              boolean mustChangePassword, LocalDateTime createdAt, LocalDateTime updatedAt) {}
}
