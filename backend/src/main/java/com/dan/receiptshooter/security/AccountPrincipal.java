package com.dan.receiptshooter.security;

import com.dan.receiptshooter.entity.UserAccount;
import java.io.Serial;
import java.util.Collection;
import java.util.List;
import org.springframework.security.core.CredentialsContainer;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

public final class AccountPrincipal implements UserDetails, CredentialsContainer {
    @Serial private static final long serialVersionUID = 1L;
    private final Long id;
    private final String username;
    private final String role;
    private final long authVersion;
    private final boolean enabled;
    private String password;

    public AccountPrincipal(UserAccount account) {
        id = account.getId(); username = account.getUsername(); role = account.getRole().name();
        authVersion = account.getAuthVersion(); enabled = account.isEnabled(); password = account.getPasswordHash();
    }

    public Long getId() { return id; }
    public long getAuthVersion() { return authVersion; }
    @Override public String getUsername() { return username; }
    @Override public String getPassword() { return password; }
    @Override public boolean isEnabled() { return enabled; }
    @Override public Collection<? extends GrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role));
    }
    @Override public void eraseCredentials() { password = null; }
}
