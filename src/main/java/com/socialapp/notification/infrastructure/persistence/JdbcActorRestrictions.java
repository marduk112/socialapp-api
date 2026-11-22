package com.socialapp.notification.infrastructure.persistence;

import com.socialapp.notification.application.ActorRestrictions;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/** JDBC adapter for {@code notification_blocks} / {@code notification_mutes} (V27); runs in the caller's transaction. */
@Repository
@RequiredArgsConstructor
class JdbcActorRestrictions implements ActorRestrictions {

    /**
     * Condition on {@code notifications n}: the notification's actor is not blocked by / blocking its recipient and not
     * muted by its recipient. Three primary-key lookups per row.
     */
    static final String NOT_SUPPRESSED = " " + """
            NOT EXISTS (SELECT 1 FROM notification_blocks b
                        WHERE b.blocker_id = n.recipient_id AND b.blocked_id = n.actor_id AND b.active)
            AND NOT EXISTS (SELECT 1 FROM notification_blocks b
                            WHERE b.blocker_id = n.actor_id AND b.blocked_id = n.recipient_id AND b.active)
            AND NOT EXISTS (SELECT 1 FROM notification_mutes m
                            WHERE m.muter_id = n.recipient_id AND m.muted_id = n.actor_id AND m.active)""";

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public int applyBlock(UUID blockerId, UUID blockedId, boolean active, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO notification_blocks (blocker_id, blocked_id, active, last_event_at)
                VALUES (:a, :b, :active, :at)
                ON CONFLICT (blocker_id, blocked_id) DO UPDATE
                   SET active = EXCLUDED.active, last_event_at = EXCLUDED.last_event_at
                 WHERE notification_blocks.last_event_at < EXCLUDED.last_event_at""",
                params(blockerId, blockedId, active, occurredAt));
    }

    @Override
    public int applyMute(UUID muterId, UUID mutedId, boolean active, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO notification_mutes (muter_id, muted_id, active, last_event_at)
                VALUES (:a, :b, :active, :at)
                ON CONFLICT (muter_id, muted_id) DO UPDATE
                   SET active = EXCLUDED.active, last_event_at = EXCLUDED.last_event_at
                 WHERE notification_mutes.last_event_at < EXCLUDED.last_event_at""",
                params(muterId, mutedId, active, occurredAt));
    }

    @Override
    public boolean suppresses(UUID recipientId, UUID actorId) {
        Boolean suppressed = jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM notification_blocks
                               WHERE active AND ((blocker_id = :r AND blocked_id = :a)
                                              OR (blocker_id = :a AND blocked_id = :r)))
                    OR EXISTS (SELECT 1 FROM notification_mutes WHERE active AND muter_id = :r AND muted_id = :a)""",
                new MapSqlParameterSource().addValue("r", recipientId).addValue("a", actorId), Boolean.class);
        return Boolean.TRUE.equals(suppressed);
    }

    private static MapSqlParameterSource params(UUID a, UUID b, boolean active, Instant at) {
        return new MapSqlParameterSource().addValue("a", a).addValue("b", b).addValue("active", active)
                .addValue("at", at.atOffset(ZoneOffset.UTC));
    }
}
