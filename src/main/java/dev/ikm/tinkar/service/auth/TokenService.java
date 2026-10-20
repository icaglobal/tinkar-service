package dev.ikm.tinkar.service.auth;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/**
 * Signs the tokens a sign-in returns, and checks the ones callers send.
 *
 * <p>A token names its author by UUID as its subject, carries the author's name for display, and
 * expires after {@code tinkar.auth.token-ttl}. It is signed with HS256 and the
 * {@code tinkar.auth.jwt-secret}, so only this service can make one, and every instance given
 * the same secret accepts it.
 */
public final class TokenService {

    /** The issuer every token names, and every token checked must name. */
    public static final String ISSUER = "tinkar-service";

    /** The claim holding the author's name. */
    public static final String NAME_CLAIM = "name";

    /** The fewest bytes a secret may have: HS256 wants a key at least as long as its hash. */
    static final int MIN_SECRET_BYTES = 32;

    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration ttl;
    private final Clock clock;

    /**
     * @param secret the signing secret, at least {@value #MIN_SECRET_BYTES} bytes as UTF-8
     * @param ttl    how long a token is good for
     * @param clock  the time tokens are issued at
     */
    public TokenService(String secret, Duration ttl, Clock clock) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            throw new IllegalArgumentException("tinkar.auth.jwt-secret must be at least " + MIN_SECRET_BYTES
                    + " bytes; generate one with: openssl rand -base64 48");
        }
        SecretKey key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        this.encoder = new NimbusJwtEncoder(new ImmutableSecret<>(key));
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withSecretKey(key).macAlgorithm(MacAlgorithm.HS256).build();
        nimbus.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefault(), new JwtIssuerValidator(ISSUER)));
        this.decoder = nimbus;
        this.ttl = ttl;
        this.clock = clock;
    }

    /**
     * A service with a random secret, for when authentication is off: it makes the beans that need
     * one, and accepts no token issued anywhere else.
     */
    public static TokenService withRandomSecret(Duration ttl) {
        byte[] bytes = new byte[48];
        new SecureRandom().nextBytes(bytes);
        return new TokenService(java.util.Base64.getEncoder().encodeToString(bytes), ttl, Clock.systemUTC());
    }

    /** A signed token and when it stops being accepted. */
    public record IssuedToken(String token, Instant expiresAt) {
    }

    /**
     * A token for an author who has just signed in.
     *
     * @param author the author
     */
    public IssuedToken issue(Authors.Author author) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(ttl);
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .subject(author.id())
                .claim(NAME_CLAIM, author.name())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, expiresAt);
    }

    /**
     * Checks a token: its signature, issuer and expiry.
     *
     * @param token the token, without the {@code Bearer} prefix
     * @return the token's claims
     * @throws org.springframework.security.oauth2.jwt.JwtException if it is not accepted
     */
    public Jwt check(String token) {
        return decoder.decode(token);
    }

    /** The decoder REST requests are checked with. */
    public JwtDecoder decoder() {
        return decoder;
    }
}
