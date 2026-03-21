package com.socialapp.socialgraph.domain;

import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.neo4j.core.schema.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Neo4j Node representing a user in the social graph.
 * The userId mirrors the UUID from PostgreSQL UserManagement context.
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

    @Relationship(type = "FOLLOWS", direction = Relationship.Direction.OUTGOING)
    private List<UserNode> following = new ArrayList<>();

    public UserNode(String userId, String username, String avatarUrl) {
        this.userId = userId;
        this.username = username;
        this.avatarUrl = avatarUrl;
    }

    public void follow(UserNode target) {
        if (!following.contains(target)) {
            following.add(target);
        }
    }

    public void unfollow(UserNode target) {
        following.remove(target);
    }

    public void updateProfile(String username, String avatarUrl) {
        this.username = username;
        this.avatarUrl = avatarUrl;
    }
}
