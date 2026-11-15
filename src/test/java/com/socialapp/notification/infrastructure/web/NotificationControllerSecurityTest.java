package com.socialapp.notification.infrastructure.web;

import com.socialapp.config.ValidationConfig;
import com.socialapp.config.security.SecurityConfig;
import com.socialapp.notification.application.NotificationCursor;
import com.socialapp.notification.application.NotificationCutoff;
import com.socialapp.notification.application.NotificationQueries;
import com.socialapp.notification.application.NotificationService;
import com.socialapp.notification.application.NotificationView;
import com.socialapp.notification.domain.NotificationRepository;
import com.socialapp.notification.domain.NotificationType;
import com.socialapp.notification.infrastructure.sse.NotificationSseService;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Web slice with the real security chain and the real {@link NotificationService}; only its ports and the SSE broker
 * are mocked. Checks the API contract, authentication, that the recipient is always the caller, and input errors.
 */
@WebMvcTest(controllers = NotificationController.class)
@Import({SecurityConfig.class, JwtService.class, NotificationService.class, ValidationConfig.class})
class NotificationControllerSecurityTest {

    private static final UUID ALICE = UUID.randomUUID();
    private static final UUID BOB = UUID.randomUUID();

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;

    @MockitoBean NotificationRepository repository;
    @MockitoBean NotificationQueries queries;
    @MockitoBean NotificationCutoff cutoff;
    @MockitoBean NotificationSseService sseService;

    private String bearer(UUID user) {
        return "Bearer " + jwtService.issueToken(user, "u");
    }

    private static NotificationView view(UUID id, Instant at, boolean read) {
        return new NotificationView(id, NotificationType.LIKE, BOB, "bob", "b.png", UUID.randomUUID(), null,
                "my post", read, at, ALICE);
    }

    @Test
    void everyEndpointRequiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/notifications")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Authentication required")));
        mvc.perform(get("/api/v1/notifications/unread-count")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/read")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/stream")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, "Bearer nope"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(queries, sseService);
    }

    @Test
    void accessTokenQueryParamIsAcceptedOnlyForTheStream() throws Exception {
        String token = jwtService.issueToken(ALICE, "alice");
        mvc.perform(get("/api/v1/notifications").param("access_token", token)).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/notifications/unread-count").param("access_token", token))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/notifications/read").param("access_token", token))
                .andExpect(status().isUnauthorized());

        var emitter = new SseEmitter();
        when(sseService.subscribe(ALICE)).thenReturn(emitter);
        var result = mvc.perform(get("/api/v1/notifications/stream").param("access_token", token))
                .andExpect(request().asyncStarted())
                .andReturn();
        verify(sseService).subscribe(ALICE); // the caller's own stream, nobody else's
        emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk()); // ASYNC re-dispatch carries no token
    }

    @Test
    void listReturnsTheContractShape_forTheCallerOnly_withCursor() throws Exception {
        var at = Instant.parse("2026-11-15T10:00:00.123456Z");
        var rows = new ArrayList<NotificationView>();
        for (int i = 0; i < 3; i++) rows.add(view(UUID.randomUUID(), at, i == 0));
        when(queries.pageAfter(eq(ALICE), any(), any(), eq(3))).thenReturn(rows);

        mvc.perform(get("/api/v1/notifications").param("size", "2").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id", is(rows.get(0).id().toString())))
                .andExpect(jsonPath("$.items[0].type", is("LIKE")))
                .andExpect(jsonPath("$.items[0].actorId", is(BOB.toString())))
                .andExpect(jsonPath("$.items[0].actorUsername", is("bob")))
                .andExpect(jsonPath("$.items[0].actorAvatarUrl", is("b.png")))
                .andExpect(jsonPath("$.items[0].postId", is(rows.get(0).postId().toString())))
                .andExpect(jsonPath("$.items[0].commentId", nullValue()))
                .andExpect(jsonPath("$.items[0].snippet", is("my post")))
                .andExpect(jsonPath("$.items[0].read", is(true)))
                .andExpect(jsonPath("$.items[1].read", is(false)))
                .andExpect(jsonPath("$.items[0].createdAt", is("2026-11-15T10:00:00.123456Z")))
                .andExpect(jsonPath("$.items[0].recipientId").doesNotExist())
                .andExpect(jsonPath("$.nextCursor", is(new NotificationCursor(at, rows.get(1).id()).encode())));
        verify(queries).pageAfter(ALICE, NotificationCursor.START.createdAt(), NotificationCursor.START.id(), 3);
    }

    @Test
    void lastPageHasNullCursor_sizeDefaultsTo20AndIsClampedTo100() throws Exception {
        when(queries.pageAfter(any(), any(), any(), anyInt())).thenReturn(List.of());
        mvc.perform(get("/api/v1/notifications").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.nextCursor", nullValue()));
        verify(queries).pageAfter(eq(ALICE), any(), any(), eq(21));
        mvc.perform(get("/api/v1/notifications").param("size", "500").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk());
        verify(queries).pageAfter(eq(ALICE), any(), any(), eq(101));
    }

    @Test
    void malformedCursorOrSizeIs400Json() throws Exception {
        for (var param : List.of(new String[]{"cursor", "garbage!"}, new String[]{"size", "0"},
                new String[]{"size", "abc"}, new String[]{"size", "-5"})) {
            mvc.perform(get("/api/v1/notifications").param(param[0], param[1])
                            .header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").isNotEmpty());
        }
        verifyNoInteractions(queries);
    }

    @Test
    void unreadCountIsTheCallers() throws Exception {
        when(queries.countUnread(ALICE)).thenReturn(7L);
        mvc.perform(get("/api/v1/notifications/unread-count").header(HttpHeaders.AUTHORIZATION, bearer(ALICE)))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"count\":7}", true));
    }

    @Test
    void markReadVariants_allScopedToTheCaller() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/notifications/read").header(HttpHeaders.AUTHORIZATION, bearer(ALICE))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"" + id + "\"]}"))
                .andExpect(status().isNoContent());
        verify(queries).markRead(eq(ALICE), eq(Set.of(id)), any());

        // no body, empty body object, empty ids: all
        mvc.perform(post("/api/v1/notifications/read").header(HttpHeaders.AUTHORIZATION, bearer(BOB)))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notifications/read").header(HttpHeaders.AUTHORIZATION, bearer(BOB))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/v1/notifications/read").header(HttpHeaders.AUTHORIZATION, bearer(BOB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[]}"))
                .andExpect(status().isNoContent());
        verify(queries, times(3)).markRead(eq(BOB), isNull(), any());

        mvc.perform(post("/api/v1/notifications/read").header(HttpHeaders.AUTHORIZATION, bearer(BOB))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"ids\":[\"not-a-uuid\"]}"))
                .andExpect(status().isBadRequest());
    }
}
