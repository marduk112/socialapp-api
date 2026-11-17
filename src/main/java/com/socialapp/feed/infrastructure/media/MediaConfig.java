package com.socialapp.feed.infrastructure.media;

import com.socialapp.feed.application.media.MediaProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** Binds {@code app.media.*} (see {@link MediaProperties}). */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {
}
