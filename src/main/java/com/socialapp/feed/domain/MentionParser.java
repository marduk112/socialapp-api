package com.socialapp.feed.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Finds {@code @mention} candidates in post text and resolves them against known users. Framework-free; the
 * frontend uses the SAME tokenization (contract):
 *
 * <ul>
 *   <li>A candidate is {@code @} immediately followed by the maximal run of {@code [\p{L}\p{N}_.-]};
 *       trailing {@code .} and {@code -} characters are trimmed off the run ({@code @bob.} -> {@code bob},
 *       {@code @bob-.} -> {@code bob}); the trimmed name must be 3..30 code points long, otherwise there is no
 *       mention there (never a prefix of a longer run).</li>
 *   <li>The {@code @} must be at the start of the text or preceded by a character that is NOT
 *       {@code [\p{L}\p{N}_.@-]}, so e-mail addresses ({@code bob@example.com}) and {@code @@bob} never
 *       match.</li>
 *   <li>Equivalent JS: {@code /(?<![\p{L}\p{N}_.@-])@([\p{L}\p{N}_.-]+)/gu}, then strip
 *       {@code /[.-]+$/} from the group and require 3..30 code points ({@code [...name].length}).</li>
 * </ul>
 *
 * Resolution ({@link #resolve}): case-insensitive ({@link Locale#ROOT} lower case) username match; an exact
 * (case-sensitive) match wins when several users differ only in case; still ambiguous or unknown -> the
 * candidate stays plain text. Self-mentions are dropped, users are de-duplicated (order of first
 * appearance) and at most {@link #MAX_PER_POST} mentions are kept.
 */
public final class MentionParser {

    public static final int MIN_NAME_LENGTH = 3;
    public static final int MAX_NAME_LENGTH = 30;
    /** Resolved mentions stored per post. */
    public static final int MAX_PER_POST = 30;
    /**
     * Safety bound on distinct candidates looked up per post. A 500-character post holds at most 100
     * ({@code "@abc "} repeated), so with the post limit this never drops a candidate.
     */
    public static final int MAX_CANDIDATES = 100;

    private MentionParser() {}

    /** Distinct candidate names as written (without '@', trailing '.'/'-' trimmed), in order of appearance. */
    public static List<String> parse(String text) {
        if (text == null || text.isEmpty()) return List.of();
        Set<String> names = new LinkedHashSet<>();
        int i = 0;
        int n = text.length();
        while (i < n && names.size() < MAX_CANDIDATES) {
            int cp = text.codePointAt(i);
            if (cp != '@' || (i > 0 && blocksAt(text.codePointBefore(i)))) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i + 1;
            int end = start;
            while (end < n) {
                int c = text.codePointAt(end);
                if (!isNameChar(c)) break;
                end += Character.charCount(c);
            }
            int trimmedEnd = end;
            while (trimmedEnd > start && isTrimmed(text.charAt(trimmedEnd - 1))) trimmedEnd--;
            int length = text.codePointCount(start, trimmedEnd);
            if (length >= MIN_NAME_LENGTH && length <= MAX_NAME_LENGTH) {
                names.add(text.substring(start, trimmedEnd));
            }
            i = Math.max(end, i + 1); // the run cannot contain another '@'
        }
        return List.copyOf(names);
    }

    /** Case-insensitive lookup key of a username or candidate. */
    public static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * Resolves {@code candidates} (from {@link #parse}) against {@code knownUsers} (any superset of the users
     * whose {@link #key} equals a candidate's key; others are ignored).
     *
     * @param authorId the post's author; mentioning yourself is not stored
     */
    public static List<Mention> resolve(List<String> candidates, Collection<Mention> knownUsers, UUID authorId) {
        if (candidates.isEmpty() || knownUsers.isEmpty()) return List.of();
        Map<String, List<Mention>> byKey = new HashMap<>();
        for (Mention user : knownUsers) {
            byKey.computeIfAbsent(key(user.username()), k -> new ArrayList<>()).add(user);
        }
        Map<UUID, Mention> resolved = new LinkedHashMap<>();
        for (String name : candidates) {
            Mention user = pick(name, byKey.getOrDefault(key(name), List.of()));
            if (user == null || user.userId().equals(authorId)) continue;
            resolved.putIfAbsent(user.userId(), user);
            if (resolved.size() == MAX_PER_POST) break;
        }
        return List.copyOf(resolved.values());
    }

    private static Mention pick(String name, List<Mention> sameKey) {
        List<Mention> exact = sameKey.stream().filter(u -> u.username().equals(name)).toList();
        if (exact.size() == 1) return exact.get(0);
        if (exact.isEmpty() && sameKey.size() == 1) return sameKey.get(0);
        return null; // unknown or ambiguous
    }

    /** {@code [\p{L}\p{N}_.-]}. */
    private static boolean isNameChar(int cp) {
        return HashtagParser.isTagChar(cp) || cp == '.' || cp == '-';
    }

    private static boolean isTrimmed(char c) {
        return c == '.' || c == '-';
    }

    /** An '@' preceded by one of {@code [\p{L}\p{N}_.@-]} does not start a mention. */
    private static boolean blocksAt(int previous) {
        return isNameChar(previous) || previous == '@';
    }
}
