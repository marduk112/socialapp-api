package com.socialapp.notification.infrastructure.persistence;

import com.socialapp.notification.application.NotificationQueries;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.domain.NotificationType;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * JDBC read side of notifications (V20). Actor display data comes from Notification's own
 * {@code notification_actor_profiles} by a LEFT JOIN in the same query (no N+1); the name carried by the event is
 * the fallback. Runs in the caller's JPA transaction (same connection).
 */
@Repository
@RequiredArgsConstructor
class JdbcNotificationQueries implements NotificationQueries {

    private static final String SELECT = """
            SELECT n.id, n.type, n.actor_id, n.post_id, n.comment_id, n.snippet, n.read_at, n.created_at,
                   n.recipient_id, COALESCE(NULLIF(p.username, ''), n.actor_username) AS actor_username,
                   p.avatar_url
            FROM notifications n LEFT JOIN notification_actor_profiles p ON p.user_id = n.actor_id
            """;

    private static final RowMapper<NotificationView> ROW = (rs, i) -> new NotificationView(
            rs.getObject("id", UUID.class),
            NotificationType.valueOf(rs.getString("type")),
            rs.getObject("actor_id", UUID.class),
            rs.getString("actor_username"),
            rs.getString("avatar_url"),
            rs.getObject("post_id", UUID.class),
            rs.getObject("comment_id", UUID.class),
            rs.getString("snippet"),
            rs.getTimestamp("read_at") != null,
            rs.getTimestamp("created_at").toInstant(),
            rs.getObject("recipient_id", UUID.class));

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public List<NotificationView> pageAfter(UUID recipientId, Instant createdAt, UUID id, int limit) {
        return jdbc.query(SELECT + """
                WHERE n.recipient_id = :recipientId AND NOT n.retracted
                  AND (n.created_at, n.id) < (:createdAt, :id)
                ORDER BY n.created_at DESC, n.id DESC
                LIMIT :limit""", new MapSqlParameterSource()
                .addValue("recipientId", recipientId)
                .addValue("createdAt", utc(createdAt))
                .addValue("id", id)
                .addValue("limit", limit), ROW);
    }

    @Override
    public List<NotificationView> findVisibleByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        return jdbc.query(SELECT + """
                WHERE n.id IN (:ids) AND NOT n.retracted
                ORDER BY n.created_at, n.id""", new MapSqlParameterSource("ids", Set.copyOf(ids)), ROW);
    }

    @Override
    public long countUnread(UUID recipientId) {
        Long count = jdbc.queryForObject("""
                SELECT count(*) FROM notifications
                WHERE recipient_id = :recipientId AND read_at IS NULL AND NOT retracted""",
                new MapSqlParameterSource("recipientId", recipientId), Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public int markRead(UUID recipientId, Collection<UUID> ids, Instant readAt) {
        var params = new MapSqlParameterSource()
                .addValue("recipientId", recipientId)
                .addValue("readAt", Timestamp.from(readAt));
        String sql = """
                UPDATE notifications SET read_at = :readAt
                WHERE recipient_id = :recipientId AND read_at IS NULL AND NOT retracted""";
        if (ids != null) {
            if (ids.isEmpty()) return 0;
            sql += " AND id IN (:ids)";
            params.addValue("ids", Set.copyOf(ids));
        }
        return jdbc.update(sql, params);
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
