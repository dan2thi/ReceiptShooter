package com.dan.receiptshooter.repository.jdbc;

import com.dan.receiptshooter.repository.PhotoBlobStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPhotoBlobStore implements PhotoBlobStore {
    private final JdbcTemplate jdbc;

    public JdbcPhotoBlobStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void store(long photoId, byte[] bytes) {
        jdbc.update("INSERT INTO receipt_photo_data (photo_id, image_data) VALUES (?, ?)",
                statement -> { statement.setLong(1, photoId); statement.setBytes(2, bytes); });
    }

    @Override
    public byte[] read(long photoId) {
        return jdbc.query("SELECT image_data FROM receipt_photo_data WHERE photo_id = ?",
                result -> result.next() ? result.getBytes(1) : null, photoId);
    }

    @Override
    public long countForReceipt(long receiptId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM receipt_photo_data d " +
                "JOIN receipt_photos p ON p.id = d.photo_id WHERE p.receipt_id = ?", Long.class, receiptId);
        return count == null ? 0 : count;
    }
}
