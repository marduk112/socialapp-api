package com.socialapp.feed.infrastructure.persistence;

import com.socialapp.feed.application.usecase.BlockMuteProjection;
import com.socialapp.feed.application.usecase.PostVisibility;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/**
 * Test support: Feed's block/mute projection (package-private JDBC adapter, the projection service) and the point checks
 * that {@code PostService} / {@code BookmarkService} need, for slice tests of any package.
 */
@TestConfiguration(proxyBeanMethods = false)
@Import({JdbcBlockMuteRepository.class, BlockMuteProjection.class, PostVisibility.class})
public class BlockMuteAdapters {
}
