package dev.ikm.tinkar.service.auth;

import dev.ikm.tinkar.service.dto.AuthResponses.AuthStatus;
import dev.ikm.tinkar.service.dto.AuthResponses.AuthorSummary;
import dev.ikm.tinkar.service.dto.AuthResponses.AuthorsResponse;
import dev.ikm.tinkar.service.dto.AuthResponses.LoginRequest;
import dev.ikm.tinkar.service.dto.AuthResponses.LoginResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

/**
 * Signing in: the authors a caller can sign in as, and a token for one of them.
 *
 * <p>Open to every caller, since a caller without a token needs it to get one. A sign-in that
 * fails says only that it failed, whether the author is unknown, has no password, or gave the
 * wrong one.
 */
@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication", description = "Sign in as an author for a token.")
@SecurityRequirements
public class AuthRestController {

    private static final Logger LOG = LoggerFactory.getLogger(AuthRestController.class);

    static final String FAILED = "Sign-in failed: check the author and password";

    private final AuthProperties auth;
    private final CredentialStore credentials;
    private final TokenService tokens;

    public AuthRestController(AuthProperties auth, CredentialStore credentials, TokenService tokens) {
        this.auth = auth;
        this.credentials = credentials;
        this.tokens = tokens;
    }

    @Operation(summary = "Whether the service needs a token",
            description = "Says whether REST and gRPC calls need a token, and, when the request sends one that " +
                    "is accepted, which author it names.")
    @GetMapping("/status")
    public AuthStatus status(@RequestHeader(value = "Authorization", required = false) String authorization) {
        return new AuthStatus(auth.enabled(), signedInAs(authorization).orElse(null));
    }

    @Operation(summary = "List the authors a caller can sign in as",
            description = "The authors Komet's author screen lists: the leaf concepts below User, by name.")
    @GetMapping("/authors")
    public AuthorsResponse authors() {
        return new AuthorsResponse(Authors.list().stream()
                .map(author -> new AuthorSummary(author.id(), author.name()))
                .toList());
    }

    @Operation(summary = "Sign in as an author",
            description = "Checks the author's password and returns a token to send as 'Authorization: Bearer " +
                    "<token>' on REST requests, and as 'authorization' metadata on gRPC calls. The author is " +
                    "named by its name or one of its UUIDs.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Signed in"),
            @ApiResponse(responseCode = "400", description = "Authentication is off on this service"),
            @ApiResponse(responseCode = "401", description = "The author or password is wrong")
    })
    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest request) {
        if (!auth.enabled()) {
            return ResponseEntity.badRequest().body(LoginResponse.failure(
                    "Authentication is off on this service (tinkar.auth.enabled=false): no token is needed"));
        }
        Optional<Authors.Author> author = Authors.find(request.username());
        // The password is checked even for an unknown author, so a failure takes as long either way.
        boolean matches = credentials.matches(author.map(Authors.Author::uuids).orElse(java.util.List.of()),
                request.password());
        if (author.isEmpty() || !matches) {
            LOG.info("Sign-in refused for '{}'", request.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(LoginResponse.failure(FAILED));
        }
        TokenService.IssuedToken token = tokens.issue(author.get());
        LOG.info("Signed in: {} ({})", author.get().name(), author.get().id());
        return ResponseEntity.ok(LoginResponse.success(token.token(), token.expiresAt().toEpochMilli(),
                new AuthorSummary(author.get().id(), author.get().name())));
    }

    private Optional<AuthorSummary> signedInAs(String authorization) {
        if (!auth.enabled() || authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        try {
            var jwt = tokens.check(authorization.substring(7).strip());
            return Optional.of(new AuthorSummary(jwt.getSubject(), jwt.getClaimAsString(TokenService.NAME_CLAIM)));
        } catch (org.springframework.security.oauth2.jwt.JwtException notAccepted) {
            return Optional.empty();
        }
    }
}
