package com.socialapp.usermanagement.infrastructure.security;

import com.socialapp.shared.security.AuthenticatedUser;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies HS256-signed access tokens.
 * <ul>
 *   <li>{@code sub} = user UUID (becomes {@code Authentication.getName()})</li>
 *   <li>{@code username} = username at issue time (used for denormalised author names)</li>
 *   <li>{@code iss} = {@value #ISSUER}, verified on parse</li>
 * </ul>
 */
@Component
public class JwtService {

    static final String ISSUER = "social-app";
    static final String USERNAME_CLAIM = "username";
    private static final int MIN_SECRET_BYTES = 32; // HS256 requires >= 256-bit key
    private static final Duration ALLOWED_CLOCK_SKEW = Duration.ofSeconds(30);

    private final SecretKey key;
    private final Duration expiration;
    private final Clock clock;

    @Autowired
    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:60}") long expirationMinutes) {
        this(secret, Duration.ofMinutes(expirationMinutes), Clock.systemUTC());
    }

    JwtService(String secret, Duration expiration, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret must be at least " + MIN_SECRET_BYTES + " bytes for HS256");
        }
        if (expiration.isNegative() || expiration.isZero()) {
            throw new IllegalStateException("app.jwt.expiration-minutes must be positive");
        }
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.expiration = expiration;
        this.clock = clock;
    }

    public String issueToken(UUID userId, String username) {
        Instant now = clock.instant();
        return Jwts.builder()
                .issuer(ISSUER)
                .subject(userId.toString())
                .claim(USERNAME_CLAIM, username)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expiration)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * @return the authenticated principal, or empty if the token is malformed, has a bad
     * signature, is expired, uses another algorithm or was issued by someone else.
     */
    public Optional<AuthenticatedUser> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .clockSkewSeconds(ALLOWED_CLOCK_SKEW.toSeconds())
                    .clock(() -> Date.from(clock.instant()))
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            if (claims.getExpiration() == null || claims.getSubject() == null) {
                return Optional.empty(); // never accept non-expiring or subject-less tokens
            }
            UUID userId = UUID.fromString(claims.getSubject());
            return Optional.of(new AuthenticatedUser(userId, claims.get(USERNAME_CLAIM, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            // IllegalArgumentException covers a missing/non-UUID subject
            return Optional.empty();
        }
    }
}
