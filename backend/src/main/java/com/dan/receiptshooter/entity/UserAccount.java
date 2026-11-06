package com.dan.receiptshooter.entity;

import com.dan.receiptshooter.domain.AccountRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(nullable = false, unique = true, length = 100)
    private String username;
    @Column(name = "password_hash", nullable = false, length = 100)
    private String passwordHash;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private AccountRole role;
    @Column(nullable = false)
    private boolean enabled;
    @Column(name = "must_change_password", nullable = false)
    private boolean mustChangePassword;
    @Column(name = "auth_version", nullable = false)
    private long authVersion;
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected UserAccount() {}

    public UserAccount(String username, String passwordHash, AccountRole role) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.role = role;
        this.enabled = true;
        this.mustChangePassword = true;
        this.createdAt = LocalDateTime.now(ZoneOffset.UTC);
        this.updatedAt = createdAt;
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public String getPasswordHash() { return passwordHash; }
    public AccountRole getRole() { return role; }
    public boolean isEnabled() { return enabled; }
    public boolean isMustChangePassword() { return mustChangePassword; }
    public long getAuthVersion() { return authVersion; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public void setPassword(String hash, boolean temporary) {
        passwordHash = hash;
        mustChangePassword = temporary;
        authVersion++;
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }

    public void setEnabled(boolean value) {
        if (enabled != value) {
            enabled = value;
            authVersion++;
            updatedAt = LocalDateTime.now(ZoneOffset.UTC);
        }
    }
}
