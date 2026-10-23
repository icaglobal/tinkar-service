package dev.ikm.tinkar.service.auth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The tokens a sign-in returns: what they say, and which ones are accepted. */
class TokenServiceTest {

    private static final String SECRET = "a-secret-of-at-least-thirty-two-bytes-for-tests";

    private static final Authors.Author AUTHOR = new Authors.Author(
            "11111111-2222-3333-4444-555555555555",
            List.of(UUID.fromString("11111111-2222-3333-4444-555555555555")), "Test author", 42L);

    @Test
    void aTokenNamesItsAuthorAndExpiresAfterItsLifetime() {
        Instant now = Instant.now();
        TokenService tokens = new TokenService(SECRET, Duration.ofHours(8), Clock.fixed(now, ZoneOffset.UTC));

        TokenService.IssuedToken issued = tokens.issue(AUTHOR);
        Jwt jwt = tokens.check(issued.token());

        assertThat(jwt.getSubject()).isEqualTo(AUTHOR.id());
        assertThat(jwt.getClaimAsString(TokenService.NAME_CLAIM)).isEqualTo("Test author");
        assertThat(jwt.getClaimAsString("iss")).isEqualTo(TokenService.ISSUER);
        assertThat(Duration.between(now, issued.expiresAt())).isEqualTo(Duration.ofHours(8));
        assertThat(jwt.getClaims()).doesNotContainKey("nid");
    }

    @Test
    void anExpiredTokenIsNotAccepted() {
        Clock yesterday = Clock.fixed(Instant.now().minus(Duration.ofDays(1)), ZoneOffset.UTC);
        String token = new TokenService(SECRET, Duration.ofHours(8), yesterday).issue(AUTHOR).token();

        TokenService now = new TokenService(SECRET, Duration.ofHours(8), Clock.systemUTC());
        assertThatThrownBy(() -> now.check(token)).isInstanceOf(JwtException.class).hasMessageContaining("expired");
    }

    @Test
    void aTokenSignedWithAnotherSecretIsNotAccepted() {
        String token = new TokenService("another-secret-of-at-least-thirty-two-bytes", Duration.ofHours(1),
                Clock.systemUTC()).issue(AUTHOR).token();

        TokenService tokens = new TokenService(SECRET, Duration.ofHours(1), Clock.systemUTC());
        assertThatThrownBy(() -> tokens.check(token)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> tokens.check("not a token")).isInstanceOf(JwtException.class);
    }

    @Test
    void aShortSecretIsRefused() {
        assertThatThrownBy(() -> new TokenService("too short", Duration.ofHours(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 32 bytes");
        assertThatThrownBy(() -> new TokenService(null, Duration.ofHours(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theRandomSecretAcceptsOnlyItsOwnTokens() {
        TokenService off = TokenService.withRandomSecret(Duration.ofHours(1));
        String elsewhere = new TokenService(SECRET, Duration.ofHours(1), Clock.systemUTC()).issue(AUTHOR).token();

        assertThatThrownBy(() -> off.check(elsewhere)).isInstanceOf(JwtException.class);
    }
}
