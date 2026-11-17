package com.socialapp.feed.application.timeline;

import com.socialapp.feed.domain.Post;

import java.util.List;

/** One timeline page; {@code next} is null on the last page. */
public record FeedPage(List<Post> posts, FeedCursor next) {
}
