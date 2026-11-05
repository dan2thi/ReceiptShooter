package com.dan.receiptshooter.exception;

public final class ReceiptConflictException extends RuntimeException {
    public ReceiptConflictException() { super("Receipt or photo UUID conflicts with existing data."); }
}
