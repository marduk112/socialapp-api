package com.socialapp.notification.domain;

/** What happened to the recipient. Dislikes never notify. */
public enum NotificationType {
    /** Someone follows you. */
    FOLLOW,
    /** Someone likes your post or comment. */
    LIKE,
    /** Someone comments on your post (also a reply in your post's thread, unless you already get REPLY for it). */
    COMMENT,
    /** Someone replies to your comment. */
    REPLY,
    /** Someone @mentions you in a post (or a quote's comment). */
    MENTION,
    /** Someone reposts your post. */
    REPOST,
    /** Someone quotes your post. */
    QUOTE
}
