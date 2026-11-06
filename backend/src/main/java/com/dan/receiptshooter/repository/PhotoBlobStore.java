package com.dan.receiptshooter.repository;

/** Storage boundary for original image bytes, separate from photo metadata. */
public interface PhotoBlobStore {
    void store(long photoId, byte[] bytes);

    /** Returns the original bytes, or null if the BLOB is missing. */
    byte[] read(long photoId);

    long countForReceipt(long receiptId);
}
