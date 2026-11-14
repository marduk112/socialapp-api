package com.socialapp.feed.domain;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Extracts hashtags from post text. Framework-free; the frontend implements the SAME rule (contract):
 *
 * <ul>
 *   <li>A hashtag is {@code #} immediately followed by a run of 1..50 tag characters {@code [\p{L}\p{N}_]}
 *       (Unicode letters, Unicode numbers, underscore), counted in code points. The run is maximal: if the
 *       characters after {@code #} form a run longer than 50, there is no hashtag there at all (never a
 *       50-character prefix).</li>
 *   <li>The {@code #} must be at the start of the text or preceded by a character that is NOT
 *       {@code [\p{L}\p{N}_&/]}. So {@code a#b}, {@code #tag#tag2} (the second one), HTML entities
 *       {@code &#123;} and URL fragments such as {@code http://x/#anchor} are not hashtags. ({@code /} is
 *       excluded in addition to the word characters and {@code &} precisely so that URL fragments after a
 *       path separator do not become tags.)</li>
 *   <li>The run must contain at least one letter ({@code #123}, {@code #_} are not hashtags).</li>
 *   <li>Normalised form: the run lower-cased with {@link Locale#ROOT} (JS {@code toLowerCase()}), without
 *       {@code #}; de-duplicated; order of first appearance; at most {@link #MAX_PER_POST} (extras
 *       ignored).</li>
 * </ul>
 *
 * Equivalent JS regex (needs the {@code u} flag and lookbehind):
 * {@code /(?<![\p{L}\p{N}_&\/])#([\p{L}\p{N}_]{1,50})(?![\p{L}\p{N}_])/gu} plus the "contains a letter"
 * check. The Java side scans code points by hand because Java's regex lookbehind does not see a
 * supplementary-plane letter (surrogate pair) before the {@code #}.
 */
public final class HashtagParser {

    public static final int MAX_TAG_LENGTH = 50;
    public static final int MAX_PER_POST = 30;

    private HashtagParser() {}

    /** Normalised hashtags of {@code text} (see class comment); empty for null/blank text. */
    public static List<String> parse(String text) {
        if (text == null || text.isEmpty()) return List.of();
        Set<String> tags = new LinkedHashSet<>();
        int i = 0;
        int n = text.length();
        while (i < n && tags.size() < MAX_PER_POST) {
            int cp = text.codePointAt(i);
            if (cp != '#' || (i > 0 && blocksHash(text.codePointBefore(i)))) {
                i += Character.charCount(cp);
                continue;
            }
            int start = i + 1;
            int end = start;
            int length = 0;
            boolean hasLetter = false;
            while (end < n) {
                int c = text.codePointAt(end);
                if (!isTagChar(c)) break;
                hasLetter |= Character.isLetter(c);
                length++;
                end += Character.charCount(c);
            }
            if (length >= 1 && length <= MAX_TAG_LENGTH && hasLetter) {
                tags.add(normalise(text.substring(start, end)));
            }
            i = end; // the run (if any) cannot contain another '#'
        }
        return List.copyOf(tags);
    }

    /**
     * Normalises a hashtag given by a client (e.g. the {@code {tag}} path segment): one leading {@code #} is
     * tolerated and stripped, the rest must be a complete valid tag (1..50 tag characters, at least one
     * letter), and is lower-cased like {@link #parse}.
     *
     * @throws IllegalArgumentException (400) if it is not a valid hashtag
     */
    public static String normaliseQuery(String raw) {
        String s = raw == null ? "" : raw.strip();
        if (s.startsWith("#")) s = s.substring(1);
        if (!isValidTag(s)) {
            throw new IllegalArgumentException("Invalid hashtag: expected 1-" + MAX_TAG_LENGTH
                    + " letters, digits or underscores including at least one letter");
        }
        return normalise(s);
    }

    /** True if {@code s} (without '#') is a complete valid tag. */
    static boolean isValidTag(String s) {
        if (s == null || s.isEmpty()) return false;
        int length = 0;
        boolean hasLetter = false;
        for (int i = 0; i < s.length(); ) {
            int c = s.codePointAt(i);
            if (!isTagChar(c)) return false;
            hasLetter |= Character.isLetter(c);
            length++;
            i += Character.charCount(c);
        }
        return length <= MAX_TAG_LENGTH && hasLetter;
    }

    private static String normalise(String tag) {
        return tag.toLowerCase(Locale.ROOT);
    }

    /** {@code [\p{L}\p{N}_]}. */
    static boolean isTagChar(int cp) {
        return Character.isLetter(cp) || isUnicodeNumber(cp) || cp == '_';
    }

    /** {@code \p{N}}: decimal digits, letter numbers (e.g. Roman numerals) and other numbers. */
    static boolean isUnicodeNumber(int cp) {
        int type = Character.getType(cp);
        return type == Character.DECIMAL_DIGIT_NUMBER || type == Character.LETTER_NUMBER
                || type == Character.OTHER_NUMBER;
    }

    /** A '#' preceded by one of {@code [\p{L}\p{N}_&/]} does not start a hashtag. */
    private static boolean blocksHash(int previous) {
        return isTagChar(previous) || previous == '&' || previous == '/';
    }
}
