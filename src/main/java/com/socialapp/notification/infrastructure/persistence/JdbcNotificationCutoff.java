package com.socialapp.notification.infrastructure.persistence;

import com.socialapp.notification.application.NotificationCutoff;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;

/** Reads {@code notification_cutoff} (written once by V20) on first use and caches it: it never changes. */
@Component
@RequiredArgsConstructor
class JdbcNotificationCutoff implements NotificationCutoff {

    private final JdbcTemplate jdbc;
    private volatile Instant since;

    @Override
    public Instant since() {
        Instant cached = since;
        if (cached == null) {
            Timestamp ts = jdbc.queryForObject("SELECT since FROM notification_cutoff WHERE id = 1", Timestamp.class);
            cached = ts == null ? Instant.EPOCH : ts.toInstant();
            since = cached;
        }
        return cached;
    }
}
