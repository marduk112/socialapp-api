package com.socialapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Static resources only: avatars ({@code /uploads/avatars/**} from {@code app.upload.dir}). Post images
 * ({@code /uploads/media/**}) are served by {@code feed.infrastructure.media.MediaFileController}, which validates the
 * key and sets the type explicitly; no static handler maps that path. CORS is configured in
 * {@link com.socialapp.config.security.SecurityConfig#corsConfigurationSource} so that
 * preflight requests are answered before authentication runs.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String avatarDir;

    public WebConfig(@Value("${app.upload.dir:./uploads/avatars}") String avatarDir) {
        this.avatarDir = avatarDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(avatarDir).toAbsolutePath().normalize().toUri().toString();
        registry.addResourceHandler("/uploads/avatars/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/");
    }
}
