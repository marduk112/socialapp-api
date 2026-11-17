package com.socialapp.usermanagement.infrastructure.web;

import com.socialapp.config.security.SecurityConfig;
import com.socialapp.shared.domain.ConflictException;
import com.socialapp.shared.security.AuthenticatedUser;
import com.socialapp.socialgraph.application.command.FollowUserCommand;
import com.socialapp.socialgraph.application.usecase.SocialGraphService;
import com.socialapp.socialgraph.infrastructure.web.SocialGraphController;
import com.socialapp.usermanagement.application.command.CreateUserCommand;
import com.socialapp.usermanagement.application.command.UpdateUserCommand;
import com.socialapp.usermanagement.application.usecase.UserService;
import com.socialapp.usermanagement.domain.User;
import com.socialapp.usermanagement.infrastructure.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Security behaviour of the UserManagement and SocialGraph endpoints. No Docker/infra required. */
@WebMvcTest(controllers = {UserController.class, AvatarController.class, SocialGraphController.class})
@Import({SecurityConfig.class, JwtService.class})
class UserControllerSecurityTest {

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired JsonMapper objectMapper;

    @MockitoBean UserService userService;
    @MockitoBean SocialGraphService socialGraphService;

    private final User alice = User.register("alice", "alice@test.com", "hash", null);
    private final UUID bobId = UUID.randomUUID();

    private String bearer(User user) {
        return "Bearer " + jwtService.issueToken(user.getId(), user.getUsername());
    }

    @Test
    void loginIsPublicAndReturnsUserFieldsPlusToken() throws Exception {
        when(userService.login("alice", "secret123")).thenReturn(alice);

        String body = mvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is(alice.getId().toString())))
                .andExpect(jsonPath("$.username", is("alice")))
                .andExpect(jsonPath("$.email", is("alice@test.com")))
                .andReturn().getResponse().getContentAsString();

        JsonNode json = objectMapper.readTree(body);
        assertThat(jwtService.parse(json.get("token").asString()))
                .contains(new AuthenticatedUser(alice.getId(), "alice"));
    }

    @Test
    void loginStillWorksWithAStaleTokenHeader() throws Exception {
        when(userService.login("alice", "secret123")).thenReturn(alice);

        mvc.perform(post("/api/v1/users/login")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer expired-or-garbage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"alice\",\"password\":\"secret123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void loginWithBadCredentialsReturns401() throws Exception {
        when(userService.login(any(), any())).thenThrow(new SecurityException("Invalid credentials"));

        mvc.perform(post("/api/v1/users/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"identifier\":\"alice\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error", is("Invalid credentials")));
    }

    @Test
    void registerIsPublicAndReturnsToken() throws Exception {
        when(userService.handle(any(CreateUserCommand.class))).thenReturn(alice);

        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"alice@test.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id", is(alice.getId().toString())))
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void registerWithInvalidEmailReturns400() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"not-an-email\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").isNotEmpty())
                .andExpect(jsonPath("$.timestamp").isNotEmpty());

        verify(userService, never()).handle(any(CreateUserCommand.class));
    }

    @Test
    void registerWithShortPasswordReturns400() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"email\":\"alice@test.com\",\"password\":\"short\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).handle(any(CreateUserCommand.class));
    }

    @Test
    void registerWithBlankUsernameReturns400() throws Exception {
        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"  \",\"email\":\"alice@test.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).handle(any(CreateUserCommand.class));
    }

    @Test
    void registerWithDuplicateEmailReturns409EvenWithDifferentCase() throws Exception {
        when(userService.handle(any(CreateUserCommand.class)))
                .thenThrow(new ConflictException("Email already registered: alice@test.com"));

        mvc.perform(post("/api/v1/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice2\",\"email\":\"ALICE@Test.com\",\"password\":\"secret123\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error", is("Email already registered: alice@test.com")));
    }

    @Test
    void updateWithBlankUsernameReturns400() throws Exception {
        mvc.perform(put("/api/v1/users/{id}", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"bio\":\"hi\"}"))
                .andExpect(status().isBadRequest());

        verify(userService, never()).handle(any(UpdateUserCommand.class));
    }

    @Test
    void updateWithTakenUsernameReturns409() throws Exception {
        when(userService.handle(any(UpdateUserCommand.class)))
                .thenThrow(new ConflictException("Username already taken: bob"));

        mvc.perform(put("/api/v1/users/{id}", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void getUserRequiresToken() throws Exception {
        mvc.perform(get("/api/v1/users/{id}", alice.getId()))
                .andExpect(status().isUnauthorized());

        when(userService.findById(alice.getId())).thenReturn(alice);
        mvc.perform(get("/api/v1/users/{id}", alice.getId()).header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void updatingAnotherUsersProfileIsForbidden() throws Exception {
        mvc.perform(put("/api/v1/users/{id}", bobId)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"pwned\"}"))
                .andExpect(status().isForbidden());

        verify(userService, never()).handle(any(UpdateUserCommand.class));
    }

    @Test
    void updatingOwnProfileReturnsFreshToken() throws Exception {
        when(userService.handle(any(UpdateUserCommand.class))).thenReturn(alice);

        mvc.perform(put("/api/v1/users/{id}", alice.getId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"bio\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());
    }

    @Test
    void avatarUploadRequiresTokenAndOwnership() throws Exception {
        var file = new MockMultipartFile("file", "a.png", "image/png", new byte[]{1, 2, 3});

        mvc.perform(multipart("/api/v1/users/{id}/avatar", alice.getId()).file(file))
                .andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/v1/users/{id}/avatar", bobId).file(file)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isForbidden());

        verify(userService, never()).updateAvatar(any(), any());
    }

    /** The global multipart limit was raised to 5 MB for post images (3C); the avatar keeps its own 2 MB limit. */
    @Test
    void avatarLargerThan2MbIsStillRejectedWith413() throws Exception {
        var big = new MockMultipartFile("file", "a.png", "image/png", new byte[2 * 1024 * 1024 + 1]);

        mvc.perform(multipart("/api/v1/users/{id}/avatar", alice.getId()).file(big)
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isContentTooLarge());

        verify(userService, never()).updateAvatar(any(), any());
    }

    @Test
    void followUsesPrincipalAsFollowerAndIgnoresBodyFollowerId() throws Exception {
        UUID carol = UUID.randomUUID();

        mvc.perform(post("/api/v1/social/follow")
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followerId\":\"" + bobId + "\",\"followeeId\":\"" + carol + "\"}"))
                .andExpect(status().isOk());

        verify(socialGraphService).follow(new FollowUserCommand(alice.getId(), carol));
    }

    @Test
    void unfollowWithoutTokenIsRejected() throws Exception {
        mvc.perform(delete("/api/v1/social/follow")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"followeeId\":\"" + bobId + "\"}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(socialGraphService);
    }

    @Test
    void isFollowingDefaultsFollowerToCaller() throws Exception {
        when(socialGraphService.isFollowing(alice.getId().toString(), bobId.toString())).thenReturn(true);

        mvc.perform(get("/api/v1/social/is-following")
                        .param("followeeId", bobId.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer(alice)))
                .andExpect(status().isOk())
                .andExpect(content().string("true"));

        verify(socialGraphService).isFollowing(eq(alice.getId().toString()), eq(bobId.toString()));
    }
}
