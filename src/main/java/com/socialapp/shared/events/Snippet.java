package com.socialapp.shared.events;

/**
 * Short plain-text excerpt carried by events and shown in notifications: whitespace runs (incl. newlines) collapsed
 * to one space, trimmed, at most {@link #MAX_CODE_POINTS} Unicode code points (a longer text is cut to
 * {@code MAX - 1} code points + "…"; a surrogate pair is never split). Blank or null text -> null.
 */
public final class Snippet {

    public static final int MAX_CODE_POINTS = 120;
    private static final String ELLIPSIS = "…";

    private Snippet() {
    }

    public static String of(String text) {
        if (text == null) return null;
        String collapsed = text.replaceAll("\\s+", " ").strip();
        if (collapsed.isEmpty()) return null;
        int length = collapsed.codePointCount(0, collapsed.length());
        if (length <= MAX_CODE_POINTS) return collapsed;
        int end = collapsed.offsetByCodePoints(0, MAX_CODE_POINTS - 1);
        return collapsed.substring(0, end).stripTrailing() + ELLIPSIS;
    }
}
