package com.socialapp.feed.domain;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface PostMediaRepository extends JpaRepository<PostMedia, UUID> {

    /**
     * Locks the given uploads (SELECT ... FOR UPDATE, in id order so concurrent attaches of overlapping sets cannot
     * deadlock) for the create transaction. Missing ids are simply absent.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from PostMedia m where m.id in :ids order by m.id")
    List<PostMedia> findAllByIdForUpdate(@Param("ids") Collection<UUID> ids);

    /** Media of each of {@code postIds}, ordered by post then position: ONE query on {@code ux_post_media_post_position}. */
    @Query("select m from PostMedia m where m.postId in :postIds order by m.postId, m.position")
    List<PostMedia> findByPostIds(@Param("postIds") Collection<UUID> postIds);

    /** Storage keys of the media of {@code postId} (its files are deleted after the post's deletion commits). */
    @Query("select m.storageKey from PostMedia m where m.postId = :postId")
    List<String> findStorageKeysByPostId(@Param("postId") UUID postId);

    /** Number of unattached uploads of {@code ownerId} (partial index {@code ix_post_media_unattached_owner}). */
    @Query("select count(m) from PostMedia m where m.ownerId = :ownerId and m.postId is null")
    long countUnattached(@Param("ownerId") UUID ownerId);

    /**
     * Serialises the uploads of one user (transaction-scoped advisory lock), so the pending-upload cap cannot be
     * exceeded by concurrent uploads. Class key {@code 0x4D656469} ("Medi"), object key = hash of the owner id.
     */
    @Query(value = "SELECT CAST(pg_advisory_xact_lock(1298490473, hashtext(CAST(:ownerId AS text))) AS text)",
            nativeQuery = true)
    String lockUploadsOf(@Param("ownerId") UUID ownerId);

    /**
     * Up to {@code limit} unattached uploads created before {@code cutoff}, oldest first, locked FOR UPDATE SKIP LOCKED:
     * an upload being attached right now (row locked by the create transaction) is skipped, never waited for.
     */
    @Query(value = """
            SELECT m.* FROM post_media m
            WHERE m.post_id IS NULL AND m.created_at < :cutoff
            ORDER BY m.created_at
            LIMIT :limit
            FOR UPDATE SKIP LOCKED""", nativeQuery = true)
    List<PostMedia> lockExpiredUnattached(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Deletes the given rows if they are still unattached; returns the number deleted. */
    @Modifying(flushAutomatically = true)
    @Query("delete from PostMedia m where m.id in :ids and m.postId is null")
    int deleteUnattached(@Param("ids") Collection<UUID> ids);
}
