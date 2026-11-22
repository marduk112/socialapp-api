package com.socialapp.socialgraph.infrastructure.persistence;

import com.socialapp.socialgraph.application.usecase.FollowSuggestion;
import com.socialapp.socialgraph.application.usecase.FollowSuggestionQueries;
import lombok.RequiredArgsConstructor;
import org.neo4j.driver.Record;
import org.springframework.data.neo4j.core.Neo4jClient;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
class Neo4jFollowSuggestionQueries implements FollowSuggestionQueries {

    private static final String FRIENDS_OF_FRIENDS = """
            MATCH (me:User {userId: $viewerId})-[:FOLLOWS]->(f:User)-[:FOLLOWS]->(s:User)
            WHERE s <> me AND s.username IS NOT NULL AND NOT EXISTS { (me)-[:FOLLOWS]->(s) }
              AND NOT EXISTS { (me)-[:BLOCKS|MUTES]->(s) } AND NOT EXISTS { (s)-[:BLOCKS]->(me) }
            RETURN s.userId AS userId, s.username AS username, s.avatarUrl AS avatarUrl,
                   count(DISTINCT f) AS mutual
            ORDER BY mutual DESC, username ASC, userId ASC
            LIMIT $limit
            """;

    private static final String MOST_FOLLOWED = """
            MATCH (s:User)
            WHERE s.userId <> $viewerId AND s.username IS NOT NULL AND NOT s.userId IN $excludeIds
              AND NOT EXISTS { (:User {userId: $viewerId})-[:FOLLOWS|BLOCKS|MUTES]->(s) }
              AND NOT EXISTS { (s)-[:BLOCKS]->(:User {userId: $viewerId}) }
            RETURN s.userId AS userId, s.username AS username, s.avatarUrl AS avatarUrl,
                   0 AS mutual, COUNT { (s)<-[:FOLLOWS]-(:User) } AS followers
            ORDER BY followers DESC, username ASC, userId ASC
            LIMIT $limit
            """;

    private final Neo4jClient neo4jClient;

    @Override
    public List<FollowSuggestion> friendsOfFriends(String viewerId, int limit) {
        return run(FRIENDS_OF_FRIENDS, Map.of("viewerId", viewerId, "limit", limit));
    }

    @Override
    public List<FollowSuggestion> mostFollowed(String viewerId, Collection<String> excludeIds, int limit) {
        return run(MOST_FOLLOWED, Map.of("viewerId", viewerId, "excludeIds", List.copyOf(excludeIds), "limit", limit));
    }

    private List<FollowSuggestion> run(String cypher, Map<String, Object> params) {
        return List.copyOf(neo4jClient.query(cypher).bindAll(params)
                .fetchAs(FollowSuggestion.class).mappedBy((ts, row) -> map(row)).all());
    }

    private static FollowSuggestion map(Record row) {
        var avatar = row.get("avatarUrl");
        return new FollowSuggestion(row.get("userId").asString(), row.get("username").asString(),
                avatar.isNull() ? null : avatar.asString(), row.get("mutual").asLong());
    }
}
