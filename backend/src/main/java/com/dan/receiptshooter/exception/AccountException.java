package com.dan.receiptshooter.exception;

import org.springframework.http.HttpStatus;

public class AccountException extends RuntimeException {
    private final HttpStatus status;
    public AccountException(HttpStatus status, String message) { super(message); this.status = status; }
    public HttpStatus getStatus() { return status; }
}
