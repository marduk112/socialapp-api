package com.socialapp.feed.application.usecase;

import com.socialapp.config.backfill.BackfillStateRepository;
import com.socialapp.config.backfill.StartupBackfill;
import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.MentionParser;
import com.socialapp.feed.domain.Post;
import com.socialapp.feed.domain.PostHashtagRepository;
import com.socialapp.feed.domain.PostMentionRepository;
import com.socialapp.feed.domain.PostRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * One-time backfill of {@code post_hashtags} / {@code post_mentions} (V17) for posts written before stage 2B, which
 * have no index rows until edited. Done in Java, not in Flyway: the rules need {@code \p{L}}/{@code \p{N}} and the
 * code-point scanner of {@link com.socialapp.feed.domain.HashtagParser}/{@link MentionParser}.
 *
 * <h2>What it writes</h2>
 * Exactly what create/edit writes ({@code PostService.saveWithTextIndex}): {@link Post#hashtags()} through
 * {@link PostHashtagRepository#replace} (which copies {@code posts.created_at} into the rows) and the mentions
 * resolved by {@link MentionLookup} + {@link MentionParser#resolve} (only against {@code author_profiles}) through
 * {@link PostMentionRepository#replace}. REPOSTs are skipped (no text). Posts that already have rows (created or
 * edited since 2B) are never touched.
 *
 * <h2>Progress, completion, instances</h2>
 * <ul>
 *   <li>Keyset scan over {@code posts.id} (candidates: not REPOST, no rows yet), {@code batch-size} per
 *       transaction. The cursor is stored in {@code backfill_state} in the same transaction as the batch's rows, so
 *       the run is resumable and a post without any valid tag is passed once, never rescanned.</li>
 *   <li>Each batch transaction first takes {@code pg_try_advisory_xact_lock} for this backfill and reads the
 *       cursor under it, so two instances never process the same batch; the one that misses the lock waits briefly
 *       and continues from the shared cursor. When the scan is exhausted, {@code completed_at} is set and later
 *       starts only do one lookup.</li>
 *   <li>Concurrency with live traffic: the batch locks its posts with {@code FOR UPDATE SKIP LOCKED} and re-checks
 *       "no rows yet" under the lock, so an edit committing first wins (its rows exist, the post is skipped) and an
 *       edit coming later waits and then replaces our rows with its own. The batch never waits for a row lock, so
 *       it cannot deadlock with e.g. a delete locking an original and then its quotes. Rows skipped because someone
 *       else held them are retried one by one right after the batch (a single-row wait cannot deadlock); the cursor
 *       of that batch stops before the first skipped row, so a crash before the retry loses nothing.</li>
 * </ul>
 */
@Component
@Slf4j
public class PostTextIndexBackfill implements StartupBackfill {

    public static final String NAME = "post-text-index-v17";

    /** Smallest uuid in Postgres order: the scan starts after it. */
    private static final UUID MIN_UUID = new UUID(0L, 0L);
    private static final long LOCK_RETRY_MS = 200;

    private final PostRepository posts;
    private final PostHashtagRepository hashtags;
    private final PostMentionRepository mentions;
    private final AuthorProfileRepository profiles;
    private final BackfillStateRepository state;
    private final TransactionOperations transactions;
    private final int batchSize;

    @Autowired
    public PostTextIndexBackfill(PostRepository posts, PostHashtagRepository hashtags, PostMentionRepository mentions,
                                 AuthorProfileRepository profiles, BackfillStateRepository state,
                                 PlatformTransactionManager transactionManager,
                                 @Value("${app.backfill.post-text.batch-size:500}") int batchSize) {
        this(posts, hashtags, mentions, profiles, state, new TransactionTemplate(transactionManager), batchSize);
    }

    PostTextIndexBackfill(PostRepository posts, PostHashtagRepository hashtags, PostMentionRepository mentions,
                          AuthorProfileRepository profiles, BackfillStateRepository state,
                          TransactionOperations transactions, int batchSize) {
        if (batchSize < 1) throw new IllegalArgumentException("batch size must be >= 1");
        this.posts = posts;
        this.hashtags = hashtags;
        this.mentions = mentions;
        this.profiles = profiles;
        this.state = state;
        this.transactions = transactions;
        this.batchSize = batchSize;
    }

    @Override
    public String name() {
        return NAME;
    }

    /** Result of one batch transaction. */
    private record Batch(boolean locked, boolean complete, int scanned, int indexed, long rows, List<UUID> skipped) {
        static final Batch LOCKED = new Batch(true, false, 0, 0, 0, List.of());
        static final Batch COMPLETE = new Batch(false, true, 0, 0, 0, List.of());
    }

    @Override
    public Outcome run(BooleanSupplier stopRequested) {
        if (state.isCompleted(NAME)) return Outcome.done();
        state.ensure(NAME);
        long scanned = 0, indexed = 0, rows = 0;
        while (true) {
            if (stopRequested.getAsBoolean()) return new Outcome(false, true, scanned, rows);
            Batch batch = transactions.execute(status -> runBatch());
            if (batch == null) throw new IllegalStateException("backfill batch returned no result");
            if (batch.locked()) { // another instance is running a batch right now
                if (!pause(stopRequested)) return new Outcome(false, true, scanned, rows);
                continue;
            }
            scanned += batch.scanned();
            indexed += batch.indexed();
            rows += batch.rows();
            for (UUID id : batch.skipped()) {
                if (stopRequested.getAsBoolean()) return new Outcome(false, true, scanned, rows);
                Long written = transactions.execute(status -> indexOneWaitingForLock(id));
                if (written != null && written > 0) {
                    indexed++;
                    rows += written;
                }
            }
            if (batch.complete()) {
                log.info("Hashtag/mention backfill: {} legacy post(s) scanned, {} got index rows ({} row(s))",
                        scanned, indexed, rows);
                return new Outcome(false, false, scanned, rows);
            }
        }
    }

    /** One batch, in one transaction: lock, cursor, candidates, index, progress (and completion). */
    private Batch runBatch() {
        if (!state.tryLock(NAME)) return Batch.LOCKED;
        var current = state.find(NAME).orElseThrow(() -> new IllegalStateException("backfill_state row missing"));
        if (current.completed()) return Batch.COMPLETE;

        UUID after = current.cursor() == null ? MIN_UUID : UUID.fromString(current.cursor());
        List<UUID> candidates = posts.findTextIndexBackfillCandidates(after, batchSize); // in Postgres id order
        if (candidates.isEmpty()) {
            state.complete(NAME);
            return Batch.COMPLETE;
        }

        List<Post> locked = posts.lockForTextIndexBackfill(candidates);
        Set<UUID> lockedIds = new HashSet<>();
        locked.forEach(p -> lockedIds.add(p.getId()));
        // Re-checked under the row locks: an edit/create that committed meanwhile has written rows -> leave it alone.
        Set<UUID> alreadyIndexed = new HashSet<>(hashtags.findByPostIds(lockedIds).keySet());
        alreadyIndexed.addAll(mentions.findByPostIds(lockedIds).keySet());
        List<Post> todo = locked.stream().filter(p -> !alreadyIndexed.contains(p.getId())).toList();
        long[] written = index(todo);

        // Rows locked by someone else (or deleted meanwhile): retried by the caller after this commit. The cursor
        // must not pass the first of them, so a crash before the retry rescans from there instead of skipping it.
        List<UUID> skipped = candidates.stream().filter(id -> !lockedIds.contains(id)).toList();
        String cursor = current.cursor();
        for (UUID id : candidates) {
            if (!skipped.isEmpty() && id.equals(skipped.get(0))) break;
            cursor = id.toString();
        }
        state.saveProgress(NAME, cursor, todo.size());
        boolean complete = candidates.size() < batchSize && skipped.isEmpty();
        if (complete) state.complete(NAME);
        return new Batch(false, complete, candidates.size(), (int) written[0], written[1], skipped);
    }

    /** Fallback for a row that was locked by someone else: wait for that one lock (holding no other), then index. */
    private long indexOneWaitingForLock(UUID id) {
        var post = posts.findByIdForUpdate(id).filter(p -> !p.isRepost());
        if (post.isEmpty()) return 0; // deleted meanwhile
        if (hashtags.findByPostIds(List.of(id)).containsKey(id) || mentions.findByPostIds(List.of(id)).containsKey(id)) {
            return 0; // indexed by an edit meanwhile
        }
        return index(List.of(post.get()))[1];
    }

    /**
     * Writes hashtags and resolved mentions of {@code batch} (posts locked by the caller, without rows yet) with ONE
     * profile lookup for the whole batch. Posts without tags/mentions need no statement at all (they have no rows).
     *
     * @return {posts that got at least one row, rows written}
     */
    private long[] index(List<Post> batch) {
        if (batch.isEmpty()) return new long[2];
        Map<Post, List<String>> candidates = new LinkedHashMap<>();
        Set<String> keys = new HashSet<>();
        for (Post post : batch) {
            var names = post.mentionCandidates();
            candidates.put(post, names);
            names.forEach(n -> keys.add(MentionParser.key(n)));
        }
        List<Mention> known = MentionLookup.knownUsers(profiles, keys);

        long postsWithRows = 0, rows = 0;
        for (var entry : candidates.entrySet()) {
            Post post = entry.getKey();
            List<String> tags = post.hashtags();
            List<Mention> resolved = MentionParser.resolve(entry.getValue(), known, post.getAuthorId());
            if (!tags.isEmpty()) hashtags.replace(post.getId(), tags);
            if (!resolved.isEmpty()) mentions.replace(post.getId(), resolved);
            if (!tags.isEmpty() || !resolved.isEmpty()) postsWithRows++;
            rows += tags.size() + resolved.size();
        }
        return new long[] {postsWithRows, rows};
    }

    /** @return false if a stop was requested or the thread was interrupted */
    private static boolean pause(BooleanSupplier stopRequested) {
        try {
            Thread.sleep(LOCK_RETRY_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return !stopRequested.getAsBoolean();
    }
}
