package com.socialapp.config.backfill;

import java.util.function.BooleanSupplier;

/**
 * A one-time data backfill that {@link StartupBackfillRunner} runs asynchronously after the application is ready.
 *
 * <p>Contract for implementations:
 * <ul>
 *   <li><b>Durable completion marker</b> in the store that owns the data: once complete, {@link #run} returns
 *       {@link Outcome#alreadyDone()} after one cheap lookup, so restarts never redo the work.</li>
 *   <li><b>Resumable and batched</b>: progress (a keyset cursor) is persisted with each batch, so a crash or
 *       shutdown resumes where it stopped.</li>
 *   <li><b>Safe on several instances at once</b> and concurrently with live traffic (each implementation documents
 *       how).</li>
 *   <li><b>Cooperative stop</b>: checks {@code stopRequested} between batches/items and returns promptly.</li>
 * </ul>
 */
public interface StartupBackfill {

    /** Stable name; also the name of the completion marker. */
    String name();

    /**
     * Runs the backfill to completion (or until {@code stopRequested} returns true).
     *
     * @throws RuntimeException on infrastructure failure; the runner retries later (progress is kept)
     */
    Outcome run(BooleanSupplier stopRequested);

    /**
     * @param alreadyDone the marker said "complete" before this run did anything
     * @param stopped     the run returned early because a stop was requested (not complete)
     * @param items       items examined by this run (followers, posts)
     * @param written     records written by this run (outbox events, index rows)
     */
    record Outcome(boolean alreadyDone, boolean stopped, long items, long written) {

        public static Outcome done() {
            return new Outcome(true, false, 0, 0);
        }

        @Override
        public String toString() {
            if (alreadyDone) return "already complete (marker present), nothing to do";
            return (stopped ? "STOPPED before completion" : "completed") + ": " + items + " item(s), "
                    + written + " record(s) written";
        }
    }
}
