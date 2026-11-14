package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.domain.Mention;
import com.socialapp.feed.domain.PostMentionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.*;

/** JDBC adapter for {@code post_mentions} (V17). Runs in the caller's JPA transaction (same connection). */
@Repository
@RequiredArgsConstructor
class JdbcPostMentionRepository implements PostMentionRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void replace(UUID postId, List<Mention> mentions) {
        jdbc.update("DELETE FROM post_mentions WHERE post_id = :postId", Map.of("postId", postId));
        if (mentions.isEmpty()) return;
        var batch = new MapSqlParameterSource[mentions.size()];
        for (int i = 0; i < mentions.size(); i++) {
            batch[i] = new MapSqlParameterSource()
                    .addValue("postId", postId)
                    .addValue("userId", mentions.get(i).userId())
                    .addValue("username", mentions.get(i).username())
                    .addValue("position", (short) i);
        }
        jdbc.batchUpdate("""
                INSERT INTO post_mentions (post_id, mentioned_user_id, username, position)
                VALUES (:postId, :userId, :username, :position)""", batch);
    }

    @Override
    public Map<UUID, List<Mention>> findByPostIds(Collection<UUID> postIds) {
        if (postIds.isEmpty()) return Map.of();
        Map<UUID, List<Mention>> result = new HashMap<>();
        jdbc.query("""
                SELECT post_id, mentioned_user_id, username FROM post_mentions
                WHERE post_id IN (:ids) ORDER BY post_id, position""",
                Map.of("ids", Set.copyOf(postIds)),
                rs -> {
                    result.computeIfAbsent(rs.getObject("post_id", UUID.class), k -> new ArrayList<>())
                            .add(new Mention(rs.getObject("mentioned_user_id", UUID.class), rs.getString("username")));
                });
        return result;
    }
}
