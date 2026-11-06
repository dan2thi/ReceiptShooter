package com.dan.receiptshooter.service.model;

/** Original photograph bytes and the validated content type. */
public record StoredImage(String contentType, byte[] data) {}
