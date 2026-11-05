package com.dan.receiptshooter.config;

import com.zaxxer.hikari.HikariDataSource;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
class DatabaseConfiguration {
    @Bean
    @ConfigurationProperties("spring.datasource.hikari")
    HikariDataSource receiptDataSource(DataSourceProperties properties,
                                       @Value("${receipt.upload.max-photo-bytes}") long maxPhotoBytes) {
        String url = properties.determineUrl();
        validateUrl(url);
        HikariDataSource dataSource = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        if (url.startsWith("jdbc:mariadb:")) {
            // Binary prepared statements avoid text-protocol expansion of image bytes.
            dataSource.addDataSourceProperty("useServerPrepStmts", "true");
            dataSource.addDataSourceProperty("cachePrepStmts", "false");
            // Existing photos must remain retrievable if the current upload limit is lowered.
            dataSource.addDataSourceProperty("maxAllowedPacket",
                    Long.toString(Math.max(maxPhotoBytes, 256L * 1024 * 1024) + 1024 * 1024));
        }
        return dataSource;
    }

    private static void validateUrl(String url) {
        if (!url.startsWith("jdbc:mariadb:")) return;
        final String decoded;
        try {
            decoded = URLDecoder.decode(url, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("DB_URL is invalid.");
        }
        int authorityStart = decoded.indexOf("//");
        if (authorityStart >= 0) {
            int authorityEnd = decoded.indexOf('/', authorityStart + 2);
            String authority = decoded.substring(authorityStart + 2, authorityEnd < 0 ? decoded.length() : authorityEnd);
            if (authority.contains("@")) throw new IllegalStateException("Do not put credentials in DB_URL.");
        }
        int queryStart = decoded.indexOf('?');
        if (queryStart < 0) return;
        for (String parameter : decoded.substring(queryStart + 1).split("[&;]")) {
            String key = parameter.split("=", 2)[0];
            if (key.equals("user") || key.equals("username") || key.contains("password") ||
                    key.contains("passwd") || key.contains("pwd") || key.contains("token")) {
                throw new IllegalStateException("Do not put credentials in DB_URL.");
            }
        }
    }
}
