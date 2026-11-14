package com.socialapp.feed.domain;

import com.socialapp.shared.events.ReactionEvent.ReactionType;

import java.util.EnumSet;
import java.util.Set;

/**
 * Outcome of a like/dislike toggle: which of the user's existing reactions to remove and which
 * (if any) to add. Pure decision logic shared by {@link Post} and {@link Comment}; the service
 * persists the change to the reaction table.
 *
 * <p>Rules: adding a reaction is idempotent and replaces the opposite one (a user has at most one
 * of LIKE/DISLIKE); removing a reaction the user does not have is a no-op.
 */
public record ReactionChange(Set<ReactionType> removed, ReactionType added) {

    public ReactionChange {
        removed = removed.isEmpty() ? EnumSet.noneOf(ReactionType.class) : EnumSet.copyOf(removed);
    }

    static ReactionChange decide(Set<ReactionType> current, ReactionType type, boolean add) {
        var removed = EnumSet.noneOf(ReactionType.class);
        if (add) {
            for (ReactionType existing : current) {
                if (existing != type) removed.add(existing);
            }
            return new ReactionChange(removed, current.contains(type) ? null : type);
        }
        if (current.contains(type)) removed.add(type);
        return new ReactionChange(removed, null);
    }

    public boolean isNoop() {
        return removed.isEmpty() && added == null;
    }
}
