package dev.ikm.tinkar.service.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** What the sign-in endpoints take and answer. */
public final class AuthResponses {

    private AuthResponses() {
    }

    /** Whether the service needs a token, and who the caller is signed in as, if anyone. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "Whether the service needs a token")
    public record AuthStatus(
            @Schema(description = "Whether REST and gRPC calls need a token") boolean enabled,

            @Schema(description = "The author the request's token names, if it sent one") AuthorSummary signedInAs) {
    }

    /** An author a caller can sign in as. */
    @Schema(description = "An author a caller can sign in as")
    public record AuthorSummary(
            @Schema(description = "Author public ID (UUID)") String authorId,

            @Schema(description = "Author name") String name) {
    }

    /** The authors a caller can sign in as, by name. */
    @Schema(description = "The authors a caller can sign in as")
    public record AuthorsResponse(
            @Schema(description = "The authors, by name") List<AuthorSummary> authors) {

        public AuthorsResponse {
            authors = authors == null ? List.of() : List.copyOf(authors);
        }
    }

    /** A sign-in. */
    @Schema(description = "A sign-in")
    public record LoginRequest(
            @Schema(description = "The author's name or UUID", example = "KometUser1") String username,

            @Schema(description = "The author's password") String password) {

        @Override
        public String toString() {
            return "LoginRequest[username=" + username + "]";
        }
    }

    /** What a sign-in answers: a token on success, why not otherwise. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Schema(description = "What a sign-in answers")
    public record LoginResponse(
            @Schema(description = "Whether the sign-in succeeded") boolean success,

            @Schema(description = "The token to send as 'Authorization: Bearer <token>'") String token,

            @Schema(description = "When the token stops being accepted, Unix epoch milliseconds") Long expiresAt,

            @Schema(description = "The author signed in as") AuthorSummary author,

            @Schema(description = "Why the sign-in failed") String errorMessage) {

        public static LoginResponse success(String token, long expiresAt, AuthorSummary author) {
            return new LoginResponse(true, token, expiresAt, author, null);
        }

        public static LoginResponse failure(String errorMessage) {
            return new LoginResponse(false, null, null, null, errorMessage);
        }
    }
}
