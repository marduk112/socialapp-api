package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.domain.PostHashtagRepository;
import com.socialapp.feed.domain.TrendingHashtag;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/**
 * JDBC adapter for {@code post_hashtags} (V17). Runs in the caller's JPA transaction (same connection).
 * {@code created_at} is copied from the post row by the INSERT itself (not bound from Java) so it equals
 * {@code posts.created_at} exactly and the hashtag timeline can page on {@code (tag, created_at, post_id)}.
 */
@Repository
@RequiredArgsConstructor
class JdbcPostHashtagRepository implements PostHashtagRepository {

    private final NamedParameterJdbcTemplate jdbc;

    @Override
    public void replace(UUID postId, List<String> tags) {
        jdbc.update("DELETE FROM post_hashtags WHERE post_id = :postId", Map.of("postId", postId));
        if (tags.isEmpty()) return;
        var batch = new MapSqlParameterSource[tags.size()];
        for (int i = 0; i < tags.size(); i++) {
            batch[i] = new MapSqlParameterSource()
                    .addValue("postId", postId)
                    .addValue("tag", tags.get(i))
                    .addValue("position", (short) i);
        }
        int[] counts = jdbc.batchUpdate("""
                INSERT INTO post_hashtags (post_id, tag, position, created_at)
                SELECT p.id, :tag, :position, p.created_at FROM posts p WHERE p.id = :postId""", batch);
        for (int count : counts) {
            if (count == 0) { // Statement.SUCCESS_NO_INFO (-2) is fine; 0 means the post row is not visible
                throw new IllegalStateException("Post " + postId + " must be flushed before indexing its hashtags");
            }
        }
    }

    @Override
    public Map<UUID, List<String>> findByPostIds(Collection<UUID> postIds) {
        if (postIds.isEmpty()) return Map.of();
        Map<UUID, List<String>> result = new HashMap<>();
        jdbc.query("SELECT post_id, tag FROM post_hashtags WHERE post_id IN (:ids) ORDER BY post_id, position",
                Map.of("ids", Set.copyOf(postIds)),
                rs -> {
                    result.computeIfAbsent(rs.getObject("post_id", UUID.class), k -> new ArrayList<>())
                            .add(rs.getString("tag"));
                });
        return result;
    }

    @Override
    public List<TrendingHashtag> trending(Instant since, int limit) {
        return jdbc.query("""
                SELECT h.tag, count(*) AS posts, count(DISTINCT p.author_id) AS authors
                FROM post_hashtags h JOIN posts p ON p.id = h.post_id
                WHERE h.created_at >= :since
                GROUP BY h.tag
                ORDER BY authors DESC, posts DESC, h.tag ASC
                LIMIT :limit""",
                new MapSqlParameterSource().addValue("since", Timestamp.from(since)).addValue("limit", limit),
                (rs, i) -> new TrendingHashtag(rs.getString("tag"), rs.getLong("posts"), rs.getLong("authors")));
    }
}
