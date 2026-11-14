package com.socialapp.feed.application.usecase;

import com.socialapp.feed.domain.AuthorProfileRepository;
import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.MentionParser;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * The one place that turns {@code @name} keys into known users, shared by create/edit ({@link PostService}) and the
 * legacy backfill ({@link PostTextIndexBackfill}) so both resolve mentions identically: ONLY against Feed's own
 * {@code author_profiles} projection (no call into UserManagement), one batched {@code lower(username) IN (...)}
 * query, re-checked in Java with {@link MentionParser#key} (authoritative, {@code Locale.ROOT}). The result may cover
 * several posts; {@link MentionParser#resolve} picks per candidate, so a superset of known users gives exactly the
 * same per-post result as a per-post lookup.
 */
final class MentionLookup {

    private MentionLookup() {
    }

    /** Known users whose canonical key is one of {@code keys} (lower-cased via {@link MentionParser#key}). */
    static List<Mention> knownUsers(AuthorProfileRepository profiles, Collection<String> keys) {
        if (keys.isEmpty()) return List.of();
        Set<String> wanted = Set.copyOf(keys);
        return profiles.findByLowerUsernameIn(wanted).stream()
                .filter(p -> p.getUsername() != null && wanted.contains(MentionParser.key(p.getUsername())))
                .map(p -> new Mention(p.getAuthorId(), p.getUsername()))
                .toList();
    }
}
