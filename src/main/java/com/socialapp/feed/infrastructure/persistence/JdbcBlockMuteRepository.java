package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.domain.BlockMuteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** JDBC adapter for {@code feed_blocks} / {@code feed_mutes} (V26); runs in the caller's JPA transaction. */
@Repository
@RequiredArgsConstructor
class JdbcBlockMuteRepository implements BlockMuteRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public int applyBlock(UUID blockerId, UUID blockedId, boolean active, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO feed_blocks (blocker_id, blocked_id, active, last_event_at)
                VALUES (:a, :b, :active, :at)
                ON CONFLICT (blocker_id, blocked_id) DO UPDATE
                   SET active = EXCLUDED.active, last_event_at = EXCLUDED.last_event_at
                 WHERE feed_blocks.last_event_at < EXCLUDED.last_event_at""",
                params(blockerId, blockedId, active, occurredAt));
    }

    @Override
    public int applyMute(UUID muterId, UUID mutedId, boolean active, Instant occurredAt) {
        return jdbc.update("""
                INSERT INTO feed_mutes (muter_id, muted_id, active, last_event_at)
                VALUES (:a, :b, :active, :at)
                ON CONFLICT (muter_id, muted_id) DO UPDATE
                   SET active = EXCLUDED.active, last_event_at = EXCLUDED.last_event_at
                 WHERE feed_mutes.last_event_at < EXCLUDED.last_event_at""",
                params(muterId, mutedId, active, occurredAt));
    }

    @Override
    public Set<UUID> blockedAmong(UUID viewerId, Collection<UUID> userIds) {
        if (viewerId == null || userIds.isEmpty()) return Set.of();
        return new HashSet<>(jdbc.queryForList("""
                SELECT blocked_id FROM feed_blocks WHERE blocker_id = :viewer AND active AND blocked_id IN (:ids)
                UNION
                SELECT blocker_id FROM feed_blocks WHERE blocked_id = :viewer AND active AND blocker_id IN (:ids)""",
                new MapSqlParameterSource().addValue("viewer", viewerId).addValue("ids", Set.copyOf(userIds)),
                UUID.class));
    }

    @Override
    public Set<UUID> blockedEitherWay(UUID viewerId) {
        return new HashSet<>(jdbc.queryForList("""
                SELECT blocked_id FROM feed_blocks WHERE blocker_id = :viewer AND active
                UNION
                SELECT blocker_id FROM feed_blocks WHERE blocked_id = :viewer AND active""",
                new MapSqlParameterSource("viewer", viewerId), UUID.class));
    }

    @Override
    public Set<UUID> mutedBy(UUID viewerId) {
        return new HashSet<>(jdbc.queryForList(
                "SELECT muted_id FROM feed_mutes WHERE muter_id = :viewer AND active",
                new MapSqlParameterSource("viewer", viewerId), UUID.class));
    }

    private static MapSqlParameterSource params(UUID a, UUID b, boolean active, Instant at) {
        return new MapSqlParameterSource().addValue("a", a).addValue("b", b).addValue("active", active)
                .addValue("at", at.atOffset(ZoneOffset.UTC));
    }
}
