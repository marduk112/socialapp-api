package com.socialapp.usermanagement.infrastructure.security;

import com.socialapp.shared.security.AuthenticatedUser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtServiceTest {

    private static final String SECRET = "test-secret-test-secret-test-secret-0123456789";
    private static final UUID USER_ID = UUID.randomUUID();

    private final JwtService jwtService = new JwtService(SECRET, 60);

    @Test
    void issuedTokenRoundTrips() {
        String token = jwtService.issueToken(USER_ID, "alice");

        assertThat(jwtService.parse(token))
                .contains(new AuthenticatedUser(USER_ID, "alice"));
    }

    @Test
    void expiredTokenIsRejected() {
        var issuedTwoHoursAgo = new JwtService(SECRET, Duration.ofMinutes(60),
                Clock.fixed(Instant.now().minus(Duration.ofHours(2)), ZoneOffset.UTC));
        String token = issuedTwoHoursAgo.issueToken(USER_ID, "alice");

        assertThat(jwtService.parse(token)).isEmpty();
    }

    @Test
    void tokenSignedWithAnotherKeyIsRejected() {
        var other = new JwtService("another-secret-another-secret-another-secret-42", 60);

        assertThat(jwtService.parse(other.issueToken(USER_ID, "alice"))).isEmpty();
    }

    @Test
    void tamperedTokenIsRejected() {
        String token = jwtService.issueToken(USER_ID, "alice");
        String[] parts = token.split("\\.");
        String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"iss\":\"social-app\",\"sub\":\"" + UUID.randomUUID() + "\",\"exp\":9999999999}")
                        .getBytes(StandardCharsets.UTF_8));

        assertThat(jwtService.parse(parts[0] + "." + forgedPayload + "." + parts[2])).isEmpty();
    }

    @Test
    void unsignedTokenIsRejected() {
        String unsigned = Jwts.builder()
                .issuer(JwtService.ISSUER)
                .subject(USER_ID.toString())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .compact();

        assertThat(jwtService.parse(unsigned)).isEmpty();
    }

    @Test
    void tokenFromAnotherIssuerIsRejected() {
        String token = Jwts.builder()
                .issuer("someone-else")
                .subject(USER_ID.toString())
                .expiration(Date.from(Instant.now().plusSeconds(3600)))
                .signWith(Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256)
                .compact();

        assertThat(jwtService.parse(token)).isEmpty();
    }

    @Test
    void garbageIsRejected() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
        assertThat(jwtService.parse("")).isEmpty();
        assertThat(jwtService.parse(null)).isEmpty();
    }

    @Test
    void shortSecretFailsFast() {
        assertThatThrownBy(() -> new JwtService("too-short", 60))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }
}
