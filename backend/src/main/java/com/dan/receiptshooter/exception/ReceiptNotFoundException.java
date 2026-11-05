package com.dan.receiptshooter.exception;

public final class ReceiptNotFoundException extends RuntimeException {
    public ReceiptNotFoundException() { super("Receipt or photo not found."); }
}
