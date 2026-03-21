package com.socialapp.socialgraph.domain;

import org.springframework.data.neo4j.repository.Neo4jRepository;
import org.springframework.data.neo4j.repository.query.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserNodeRepository extends Neo4jRepository<UserNode, String> {

    Optional<UserNode> findByUserId(String userId);

    @Query("MATCH (u:User {userId: $userId})-[:FOLLOWS]->(f:User) RETURN f")
    List<UserNode> findFollowing(String userId);

    @Query("MATCH (follower:User)-[:FOLLOWS]->(u:User {userId: $userId}) RETURN follower")
    List<UserNode> findFollowers(String userId);

    @Query("RETURN EXISTS((:User {userId: $followerId})-[:FOLLOWS]->(:User {userId: $followeeId}))")
    boolean isFollowing(String followerId, String followeeId);
}
