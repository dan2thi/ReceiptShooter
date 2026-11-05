package com.dan.receiptshooter.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PhotoBlobStore {
    private final JdbcTemplate jdbc;

    PhotoBlobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void store(long photoId, byte[] bytes) {
        jdbc.update("INSERT INTO receipt_photo_data (photo_id, image_data) VALUES (?, ?)",
                statement -> { statement.setLong(1, photoId); statement.setBytes(2, bytes); });
    }

    public byte[] read(long photoId) {
        return jdbc.query("SELECT image_data FROM receipt_photo_data WHERE photo_id = ?",
                result -> result.next() ? result.getBytes(1) : null, photoId);
    }

    public long countForReceipt(long receiptId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM receipt_photo_data d " +
                "JOIN receipt_photos p ON p.id = d.photo_id WHERE p.receipt_id = ?", Long.class, receiptId);
        return count == null ? 0 : count;
    }
}
