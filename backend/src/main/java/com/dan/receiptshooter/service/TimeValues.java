package com.dan.receiptshooter.service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

final class TimeValues {
    private TimeValues() {}

    static LocalDateTime toDatabase(Instant value) {
        return value == null ? null : LocalDateTime.ofInstant(value.truncatedTo(ChronoUnit.MICROS), ZoneOffset.UTC);
    }

    static Instant fromDatabase(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
