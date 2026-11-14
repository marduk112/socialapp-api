package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.FollowRepository;
import lombok.extern.slf4j.Slf4j;
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
 * "Is this post visible in the viewer's following timeline?" for the SSE stream, evaluated against Feed's
 * {@code follows} projection through a small per-viewer cache:
 * <ul>
 *   <li>Each entry holds the viewer's active followee set, loaded with one indexed query and kept for
 *       {@code app.feed.followees-cache-ttl} (default 5s). So a broadcast costs at most one query per
 *       connected following-scope viewer per TTL, not one per post.</li>
 *   <li>{@link #invalidate} is called when this instance applies a follow event for that follower, so on
 *       the instance that consumes the event the change takes effect immediately; on other instances
 *       within the TTL.</li>
 *   <li>Expired entries are purged lazily (at most once per TTL), so viewers that disconnected do not
 *       accumulate.</li>
 * </ul>
 * A follow made while connected therefore takes effect once its event reaches Feed (normally well under a
 * second) plus at most one TTL.
 */
@Service
@Slf4j
public class FolloweeLookup {

    private record Entry(Set<UUID> followees, Instant loadedAt) {}

    private final FollowRepository followRepository;
    private final Duration ttl;
    private final Clock clock;
    private final Map<UUID, Entry> cache = new ConcurrentHashMap<>();
    private volatile Instant lastPurge;

    @Autowired
    public FolloweeLookup(FollowRepository followRepository,
                          @Value("${app.feed.followees-cache-ttl:5s}") Duration ttl) {
        this(followRepository, ttl, Clock.systemUTC());
    }

    FolloweeLookup(FollowRepository followRepository, Duration ttl, Clock clock) {
        this.followRepository = followRepository;
        this.ttl = ttl;
        this.clock = clock;
        this.lastPurge = clock.instant();
    }

    /** True if {@code authorId} is the viewer or someone the viewer follows. */
    public boolean isVisibleInFollowing(UUID viewerId, UUID authorId) {
        if (viewerId == null || authorId == null) return false;
        return viewerId.equals(authorId) || followeesOf(viewerId).contains(authorId);
    }

    Set<UUID> followeesOf(UUID viewerId) {
        Instant now = clock.instant();
        purgeExpired(now);
        Entry entry = cache.get(viewerId);
        if (entry == null || isExpired(entry, now)) {
            entry = new Entry(Set.copyOf(followRepository.findActiveFolloweeIds(viewerId)), now);
            cache.put(viewerId, entry);
        }
        return entry.followees();
    }

    /** Drops the cached followees of {@code followerId} (call after its follows changed). */
    public void invalidate(UUID followerId) {
        if (followerId != null) cache.remove(followerId);
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
