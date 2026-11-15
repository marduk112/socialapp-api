package com.socialapp.feed.infrastructure.media;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;

/** Test support: the media storage adapter and {@code app.media.*} binding, for slice tests that import PostService. */
@TestConfiguration(proxyBeanMethods = false)
@Import({MediaConfig.class, LocalDiskMediaStorage.class})
public class MediaAdapters {
}
