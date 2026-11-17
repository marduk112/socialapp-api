package com.socialapp.config.backfill;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JDBC access to {@code backfill_state} (V19): completion marker and keyset progress of PostgreSQL backfills, plus
 * the advisory lock that serialises a backfill's batches across instances. Joins the caller's (JPA) transaction,
 * so a batch's data and its progress commit together.
 */
@Repository
public class BackfillStateRepository {

    /**
     * First key of the two-int advisory lock space used for backfills ("Soci"); the second key is
     * {@code hashtext(name)}. Postgres keeps the two-int and the bigint key spaces apart, so these locks can
     * never collide with the outbox's bigint locks.
     */
    static final int LOCK_NAMESPACE = 0x536F6369;

    /** Snapshot of a backfill's row. */
    public record State(String cursor, long processed, boolean completed) {}

    private final JdbcTemplate jdbc;

    public BackfillStateRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Creates the row (not completed, no cursor) if it does not exist yet. Idempotent and race-safe. */
    public void ensure(String name) {
        jdbc.update("INSERT INTO backfill_state (name) VALUES (?) ON CONFLICT (name) DO NOTHING", name);
    }

    public Optional<State> find(String name) {
        return jdbc.query("SELECT cursor_value, processed, completed_at IS NOT NULL AS completed "
                        + "FROM backfill_state WHERE name = ?",
                (rs, i) -> new State(rs.getString("cursor_value"), rs.getLong("processed"),
                        rs.getBoolean("completed")),
                name).stream().findFirst();
    }

    public boolean isCompleted(String name) {
        return find(name).map(State::completed).orElse(false);
    }

    /**
     * Non-blocking, transaction-scoped advisory lock for {@code name} (released at commit/rollback, so it can never
     * leak through the connection pool). Must be called inside a transaction.
     *
     * @return false if another transaction (instance) holds it right now
     */
    public boolean tryLock(String name) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?, hashtext(?))",
                Boolean.class, LOCK_NAMESPACE, name));
    }

    /** Stores the keyset position after a batch and adds {@code items} to the processed count. */
    public void saveProgress(String name, String cursor, long items) {
        jdbc.update("UPDATE backfill_state SET cursor_value = ?, processed = processed + ?, updated_at = now() "
                + "WHERE name = ?", cursor, items, name);
    }

    /** Sets the completion marker (once). */
    public void complete(String name) {
        jdbc.update("UPDATE backfill_state SET completed_at = now(), updated_at = now() "
                + "WHERE name = ? AND completed_at IS NULL", name);
    }
}
