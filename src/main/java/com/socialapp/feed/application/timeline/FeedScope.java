package com.socialapp.feed.application.timeline;

import java.util.Locale;

/** Which posts a timeline (REST page or SSE stream) shows. */
public enum FeedScope {
    /** Every post. */
    ALL,
    /** Posts of users the viewer follows, plus the viewer's own posts. */
    FOLLOWING;

    /**
     * Parses the {@code scope} request parameter ({@code all} | {@code following}, case-insensitive);
     * null/blank means {@link #ALL}.
     *
     * @throws IllegalArgumentException (400) for any other value
     */
    public static FeedScope parse(String value) {
        if (value == null || value.isBlank()) return ALL;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "all" -> ALL;
            case "following" -> FOLLOWING;
            default -> throw new IllegalArgumentException("Invalid scope '" + value + "': expected all or following");
        };
    }
}
