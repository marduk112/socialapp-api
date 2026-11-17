package com.socialapp.feed.infrastructure.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.socialapp.config.ValidationConfig;
import com.socialapp.config.security.SecurityConfig;
import com.socialapp.feed.application.media.MediaService;
import com.socialapp.feed.application.media.TestImages;
import com.socialapp.feed.domain.PostMedia;
import com.socialapp.feed.domain.PostMediaRepository;
import com.socialapp.feed.infrastructure.media.LocalDiskMediaStorage;
import com.socialapp.feed.infrastructure.media.MediaConfig;
import com.socialapp.feed.infrastructure.media.MediaFileController;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web contract of {@code POST /api/v1/media} and {@code GET /uploads/media/{key}}: real security chain, real
 * {@link MediaService} and image validation, real local-disk storage in a temp directory; only the repository (and the
 * transaction manager) are mocked.
 */
@WebMvcTest(controllers = {MediaController.class, MediaFileController.class})
@Import({SecurityConfig.class, JwtService.class, ValidationConfig.class, MediaService.class, MediaConfig.class,
        LocalDiskMediaStorage.class})
class MediaControllerTest {

    static final Path MEDIA_DIR;

    static {
        try {
            MEDIA_DIR = Files.createTempDirectory("media-web-test");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("app.media.dir", MEDIA_DIR::toString);
        registry.add("app.base-url", () -> "http://api.test");
    }

    private static final UUID ALICE = UUID.randomUUID();
    private static final ObjectMapper JSON = new ObjectMapper();

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @MockitoBean PostMediaRepository repository;
    @MockitoBean PlatformTransactionManager transactionManager;

    @BeforeEach
    void saveReturnsArgument() {
        when(repository.save(any(PostMedia.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private String bearer() {
        return "Bearer " + jwtService.issueToken(ALICE, "alice");
    }

    private ResultActions upload(MockMultipartFile file) throws Exception {
        return mvc.perform(multipart("/api/v1/media").file(file).header(HttpHeaders.AUTHORIZATION, bearer()));
    }

    private static MockMultipartFile file(String name, String contentType, byte[] bytes) {
        return new MockMultipartFile("file", name, contentType, bytes);
    }

    // ── upload ──────────────────────────────────────────────────────────────

    @Test
    void uploadWithoutTokenIs401() throws Exception {
        mvc.perform(multipart("/api/v1/media").file(file("a.png", "image/png", TestImages.png(2, 2))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").exists());
        verifyNoInteractions(repository);
    }

    @Test
    void realPngIs201WithIdUrlAndDimensions_storedUnattachedForTheCaller() throws Exception {
        var body = upload(file("cat.png", "image/png", TestImages.png(30, 20)))
                .andExpect(status().isCreated())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.url", matchesPattern("http://api\\.test/uploads/media/[0-9a-f-]{36}\\.png")))
                .andExpect(jsonPath("$.width", is(30)))
                .andExpect(jsonPath("$.height", is(20)))
                .andReturn().getResponse().getContentAsString();

        var url = JSON.readTree(body).get("url").asText();
        assertThat(MEDIA_DIR.resolve(url.substring(url.lastIndexOf('/') + 1))).exists();
        verify(repository).save(argThat(m -> m.getOwnerId().equals(ALICE) && m.getPostId() == null
                && m.getContentType().equals("image/png")));
    }

    @Test
    void typeComesFromTheBytes_notFromTheClaimedContentTypeOrFileName() throws Exception {
        upload(file("evil.html", "text/html", TestImages.png(4, 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", org.hamcrest.Matchers.endsWith(".png")));
        upload(file("photo.png", "image/png", TestImages.jpeg(4, 4)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", org.hamcrest.Matchers.endsWith(".jpg")));
        upload(file("../../etc/passwd", "application/octet-stream", TestImages.WEBP_LOSSY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", matchesPattern(".*/uploads/media/[0-9a-f-]{36}\\.webp")));
        upload(file("a.gif", null, TestImages.gif(3, 3)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.url", org.hamcrest.Matchers.endsWith(".gif")));
    }

    @Test
    void fakeImageIs415() throws Exception {
        upload(file("fake.png", "image/png", "definitely not a png".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error", is("Only JPEG, PNG, GIF and WebP images are allowed")));
        verify(repository, never()).save(any());
    }

    @Test
    void svgIs415_evenWhenLabelledAsAnImage() throws Exception {
        byte[] svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" onload=\"alert(1)\"/>".getBytes(StandardCharsets.UTF_8);
        upload(file("x.svg", "image/svg+xml", svg)).andExpect(status().isUnsupportedMediaType());
        upload(file("x.png", "image/png", svg)).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void polyglotIs415() throws Exception {
        byte[] polyglot = TestImages.concat(TestImages.png(4, 4),
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));
        upload(file("p.png", "image/png", polyglot)).andExpect(status().isUnsupportedMediaType());
    }

    @Test
    void emptyOrMissingFileOrNotMultipartIs400() throws Exception {
        upload(file("e.png", "image/png", new byte[0]))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
        mvc.perform(multipart("/api/v1/media")
                        .file(new MockMultipartFile("other", "a.png", "image/png", TestImages.png(2, 2)))
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/media").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"file\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void moreThan5MbIs413() throws Exception {
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(TestImages.png(2, 2), 0, big, 0, 8);
        upload(file("big.png", "image/png", big))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error", is("The file is larger than 5 MB")));
    }

    @Test
    void tooManyPixelsIs413() throws Exception {
        upload(file("wide.png", "image/png", TestImages.png(8001, 1))).andExpect(status().isPayloadTooLarge());
    }

    @Test
    void tooManyPendingUploadsIs429() throws Exception {
        when(repository.countUnattached(ALICE)).thenReturn(20L);
        upload(file("a.png", "image/png", TestImages.png(2, 2)))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error", org.hamcrest.Matchers.startsWith("Too many pending uploads")));
        verify(repository, never()).save(any());
    }

    // ── serving ─────────────────────────────────────────────────────────────

    @Test
    void storedFileIsServedPubliclyWithTheTypeOfItsExtension_nosniff_andASandboxCsp() throws Exception {
        byte[] png = TestImages.png(3, 3);
        var key = UUID.randomUUID() + ".png";
        Files.write(MEDIA_DIR.resolve(key), png);

        mvc.perform(get("/uploads/media/{key}", key))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; sandbox"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("max-age=86400")))
                .andExpect(content().bytes(png));
    }

    @Test
    void anythingButAStoredServerGeneratedKeyIs404OrRejected() throws Exception {
        var key = UUID.randomUUID().toString();
        Files.writeString(MEDIA_DIR.resolve(key + ".svg"), "<svg/>");
        Files.writeString(MEDIA_DIR.getParent().resolve("secret-" + key + ".png"), "secret");

        mvc.perform(get("/uploads/media/{key}", key + ".png")).andExpect(status().isNotFound()); // not stored
        mvc.perform(get("/uploads/media/{key}", key + ".svg")).andExpect(status().isNotFound()); // not our extension
        mvc.perform(get("/uploads/media/{key}", key.toUpperCase() + ".png")).andExpect(status().isNotFound());
        mvc.perform(get("/uploads/media/{key}", "x.png")).andExpect(status().isNotFound());
        int traversal = mvc.perform(get("/uploads/media/{key}", "../secret-" + key + ".png"))
                .andReturn().getResponse().getStatus();
        assertThat(traversal).isBetween(400, 404);
        int raw = mvc.perform(get("/uploads/media/../secret-" + key + ".png")).andReturn().getResponse().getStatus();
        assertThat(raw).isBetween(400, 404);
    }
}
