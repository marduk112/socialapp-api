package com.socialapp.usermanagement.infrastructure.persistence;

import com.socialapp.usermanagement.domain.UserBlockRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** JDBC adapter for {@code user_blocks} (V28); runs in the caller's JPA transaction. */
@Repository
@RequiredArgsConstructor
class JdbcUserBlockRepository implements UserBlockRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public int apply(UUID blockerId, UUID blockedId, boolean active, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO user_blocks (blocker_id, blocked_id, active, last_event_at)
                VALUES (:a, :b, :active, :at)
                ON CONFLICT (blocker_id, blocked_id) DO UPDATE
                   SET active = EXCLUDED.active, last_event_at = EXCLUDED.last_event_at
                 WHERE user_blocks.last_event_at < EXCLUDED.last_event_at""",
                new MapSqlParameterSource().addValue("a", blockerId).addValue("b", blockedId)
                        .addValue("active", active).addValue("at", occurredAt.atOffset(ZoneOffset.UTC)));
    }
}
