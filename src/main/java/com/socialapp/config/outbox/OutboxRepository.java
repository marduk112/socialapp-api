package com.socialapp.config.outbox;

import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.EntityManagerHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Plain JDBC access to {@code outbox_events}. JdbcTemplate joins the current JPA transaction
 * (JpaTransactionManager exposes its connection to JDBC), so {@link #append} is atomic with the
 * aggregate change. Being a {@code @Repository}, exceptions are translated to Spring's
 * {@code DataAccessException} hierarchy (e.g. a unique violation surfacing in the flush below becomes a
 * {@code DataIntegrityViolationException}, exactly as it would at commit).
 */
@Repository
public class OutboxRepository {

    /** Postgres advisory lock key of the relay ("SocialOB"): one publishing instance at a time. */
    static final long RELAY_LOCK_KEY = 0x536F6369616C4F42L;
    /** Advisory lock key of {@link OutboxImporter} ("SocialOI"). */
    static final long IMPORT_LOCK_KEY = 0x536F6369616C4F49L;

    private final JdbcTemplate jdbc;
    private final EntityManagerFactory entityManagerFactory;

    @Autowired
    public OutboxRepository(JdbcTemplate jdbc, EntityManagerFactory entityManagerFactory) {
        this.jdbc = jdbc;
        this.entityManagerFactory = entityManagerFactory;
    }

    /** JDBC-only variant (no JPA flush), for tests without an EntityManagerFactory. */
    OutboxRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.entityManagerFactory = null;
    }

    /** A new outbox row. */
    public record NewMessage(UUID eventId, String aggregateType, String eventType,
                             String topic, String messageKey, String payload) {}

    /** An unpublished row as seen by the relay. */
    public record PendingMessage(long id, UUID eventId, String eventType, String topic,
                                 String messageKey, String payload, int attempts) {}

    /**
     * Inserts the row in the current transaction. Pending JPA changes are flushed first, so the
     * aggregate's row locks (any UPDATE takes one) are held before the outbox id is drawn: two
     * transactions changing the same aggregate then get outbox ids in their commit order, which is what
     * per-key ordering on the relay side relies on.
     */
    public void append(NewMessage m) {
        if (entityManagerFactory != null
                && TransactionSynchronizationManager.getResource(entityManagerFactory) instanceof EntityManagerHolder holder) {
            holder.getEntityManager().flush();
        }
        jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, event_type, topic, message_key, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)""",
                m.eventId(), m.aggregateType(), m.eventType(), m.topic(), m.messageKey(), m.payload());
    }

    /**
     * Idempotent insert keyed by {@code event_id} (unique): a row for an event that is already in the
     * outbox (published or not) is left untouched. Used to import events recorded in another store
     * ({@link OutboxImporter}), where the same event may be offered more than once.
     *
     * @return true if a row was inserted, false if the event was already present
     */
    public boolean appendIfAbsent(NewMessage m) {
        return jdbc.update("""
                INSERT INTO outbox_events (event_id, aggregate_type, event_type, topic, message_key, payload)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (event_id) DO NOTHING""",
                m.eventId(), m.aggregateType(), m.eventType(), m.topic(), m.messageKey(), m.payload()) == 1;
    }

    /**
     * Non-blocking transaction-scoped advisory lock serialising {@link OutboxImporter} runs across
     * instances, so imported events enter the outbox (and hence Kafka) in the order they were read.
     */
    public boolean tryAcquireImportLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                IMPORT_LOCK_KEY));
    }

    /**
     * Transaction-scoped advisory lock (released at commit/rollback, so it can never leak through the
     * connection pool). Non-blocking: false means another instance is relaying right now.
     */
    public boolean tryAcquireRelayLock() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class,
                RELAY_LOCK_KEY));
    }

    /**
     * Oldest unpublished, unparked rows in id order, skipping every row that has a parked (poison) row
     * with the same topic+key before it - so a poison row holds back only its own key.
     */
    public List<PendingMessage> findPublishable(int limit) {
        return jdbc.query("""
                SELECT o.id, o.event_id, o.event_type, o.topic, o.message_key, o.payload::text AS payload, o.attempts
                FROM outbox_events o
                WHERE o.published_at IS NULL
                  AND o.parked_at IS NULL
                  AND NOT EXISTS (SELECT 1 FROM outbox_events p
                                  WHERE p.published_at IS NULL AND p.parked_at IS NOT NULL
                                    AND p.topic = o.topic AND p.message_key = o.message_key
                                    AND p.id < o.id)
                ORDER BY o.id
                LIMIT ?""",
                (rs, i) -> new PendingMessage(rs.getLong("id"), rs.getObject("event_id", UUID.class),
                        rs.getString("event_type"), rs.getString("topic"), rs.getString("message_key"),
                        rs.getString("payload"), rs.getInt("attempts")),
                limit);
    }

    public void markPublished(long id) {
        jdbc.update("UPDATE outbox_events SET published_at = now() WHERE id = ?", id);
    }

    public void markFailed(long id, int attempts, String error, boolean park) {
        jdbc.update("""
                UPDATE outbox_events
                SET attempts = ?, last_error = ?, parked_at = CASE WHEN ? THEN now() ELSE parked_at END
                WHERE id = ?""", attempts, error, park, id);
    }

    /** Deletes up to {@code limit} rows published before {@code cutoff}; returns the number deleted. */
    public int deletePublishedBefore(Instant cutoff, int limit) {
        return jdbc.update("""
                DELETE FROM outbox_events
                WHERE id IN (SELECT id FROM outbox_events
                             WHERE published_at IS NOT NULL AND published_at < ?
                             ORDER BY id LIMIT ?)""",
                cutoff.atOffset(ZoneOffset.UTC), limit);
    }
}
