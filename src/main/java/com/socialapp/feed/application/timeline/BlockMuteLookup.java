package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.BlockMuteRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The viewer's block/mute sets for the SSE feed stream (stage 5A), read from Feed's projection through a small
 * per-viewer cache, exactly like {@link FolloweeLookup}: entries live {@code app.feed.restrictions-cache-ttl} (default
 * 5s), {@link #invalidate} drops them when this instance applies a block/mute event for that user, expired ones are
 * purged lazily. REST list queries do NOT use this cache: they filter in SQL against the projection.
 */
@Service
public class BlockMuteLookup {

    /** What the viewer must not see: users blocked either way ({@code blocked}) and users the viewer mutes. */
    public record Restrictions(Set<UUID> blocked, Set<UUID> muted) {
        public static final Restrictions NONE = new Restrictions(Set.of(), Set.of());

        public Restrictions {
            blocked = Set.copyOf(blocked);
            muted = Set.copyOf(muted);
        }

        /** Content of {@code userId} is hidden by a block in either direction. */
        public boolean blocks(UUID userId) {
            return userId != null && blocked.contains(userId);
        }

        /** Content of {@code userId} is hidden from the viewer's lists (block either way, or muted by the viewer). */
        public boolean hides(UUID userId) {
            return userId != null && (blocked.contains(userId) || muted.contains(userId));
        }
    }

    private record Entry(Restrictions restrictions, Instant loadedAt) {}

    private final BlockMuteRepository repository;
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private volatile Instant lastPurge;

    @Autowired
    public BlockMuteLookup(BlockMuteRepository repository,
                           @Value("${app.feed.restrictions-cache-ttl:5s}") Duration ttl) {
        this(repository, ttl, Clock.systemUTC());
    }

    BlockMuteLookup(BlockMuteRepository repository, Duration ttl, Clock clock) {
        this.repository = repository;
        this.ttl = ttl;
        this.clock = clock;
        this.lastPurge = clock.instant();
    }

    public Restrictions of(UUID viewerId) {
        if (viewerId == null) return Restrictions.NONE;
        Instant now = clock.instant();
        purgeExpired(now);
        Entry entry = cache.get(viewerId);
        if (entry == null || isExpired(entry, now)) {
            entry = new Entry(new Restrictions(repository.blockedEitherWay(viewerId), repository.mutedBy(viewerId)), now);
            cache.put(viewerId, entry);
        }
        return entry.restrictions();
    }

    /** Drops the cached sets of {@code userId} (call after a block/mute involving it changed the projection). */
    public void invalidate(UUID userId) {
        if (userId != null) cache.remove(userId);
    }

    int cachedViewers() {
        return cache.size();
    }

    private boolean isExpired(Entry entry, Instant now) {
        return !entry.loadedAt().plus(ttl).isAfter(now);
    }

    private void purgeExpired(Instant now) {
        if (lastPurge.plus(ttl).isAfter(now)) return;
        lastPurge = now;
        cache.values().removeIf(e -> isExpired(e, now));
    }
}
