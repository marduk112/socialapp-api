package com.socialapp.socialgraph.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;

import java.util.Objects;

/**
 * Neo4j Node representing a user in the social graph.
 * The userId mirrors the UUID from PostgreSQL UserManagement context.
 *
 * <p>FOLLOWS relationships are deliberately not mapped as a collection: loading and re-saving a whole
 * {@code following} list is racy (a concurrent follow/unfollow or profile update would overwrite it)
 * and loads the neighbourhood on every read. They are written with single-relationship Cypher
 * ({@code MERGE}/{@code DELETE}) in {@link UserNodeRepository}.
 */
@Node("User")
@Getter
@NoArgsConstructor
public class UserNode {

    @Id
    private String userId;          // same UUID as PostgreSQL User.id

    @Property("username")
    private String username;

    @Property("avatarUrl")
    private String avatarUrl;

    public UserNode(String userId, String username, String avatarUrl) {
        this.userId = userId;
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    /** Domain rule: a user cannot follow themselves. Throws {@link IllegalArgumentException} (400). */
    public static void ensureCanFollow(String followerId, String followeeId) {
        if (followerId == null || followeeId == null) {
            throw new IllegalArgumentException("followerId and followeeId are required");
        }
        if (followerId.equals(followeeId)) {
            throw new IllegalArgumentException("A user cannot follow themselves");
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof UserNode other)) return false;
        return userId != null && userId.equals(other.userId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(userId);
    }
}
