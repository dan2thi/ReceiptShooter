package com.dan.receiptshooter.service.model;

import com.dan.receiptshooter.domain.AccountRole;
import java.time.LocalDateTime;

/** Account details safe to return to administrators; never includes credentials. */
public record AccountView(Long id, String username, AccountRole role, boolean enabled,
                          boolean mustChangePassword, LocalDateTime createdAt, LocalDateTime updatedAt) {}
