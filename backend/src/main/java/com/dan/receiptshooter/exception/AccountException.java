package com.dan.receiptshooter.exception;

import java.util.Objects;

/** Account operation failure, independent of how an API represents it. */
public final class AccountException extends RuntimeException {
    public enum Reason {
        INVALID_INPUT,
        UNAUTHENTICATED,
        NOT_FOUND,
        CONFLICT
    }

    private final Reason reason;

    public AccountException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason);
    }

    public Reason getReason() {
        return reason;
    }
}
