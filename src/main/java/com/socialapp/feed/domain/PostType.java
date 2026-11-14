package com.socialapp.feed.domain;

/**
 * Kind of a {@link Post}.
 * <ul>
 *   <li>{@link #POST}: an ordinary post with its own text.</li>
 *   <li>{@link #REPOST}: re-publishes another post unchanged; no own text ({@code content = ""}), no hashtags or
 *       mentions. At most one per (author, original). Never points at another repost (flattened).</li>
 *   <li>{@link #QUOTE}: another post plus a required comment (1..{@value Post#MAX_CONTENT_LENGTH} code points)
 *       whose hashtags/mentions are indexed like a post's. Unlimited.</li>
 * </ul>
 */
public enum PostType {
    POST, REPOST, QUOTE
}
