package dev.ikm.tinkar.service.auth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.nio.file.Path;
import java.time.Duration;

/**
 * How callers sign in: the {@code tinkar.auth.*} settings.
 *
 * <p>With authentication off, the default, every endpoint is open and writes are stamped with
 * the generic user, as before. With it on, a caller signs in as one of the knowledge base's
 * authors with a password the service holds, and sends the token it gets back on every REST and
 * gRPC call; writes are then stamped with that author.
 *
 * @param enabled           whether REST and gRPC calls need a token
 * @param jwtSecret         the key tokens are signed with (HS256): at least 32 bytes of text;
 *                          required when authentication is on
 * @param tokenTtl          how long a token is good for
 * @param credentialsFile   the authors' password hashes, one {@code <author UUID>=<bcrypt hash>}
 *                          per line, as {@code scripts/set-password.sh} writes them
 * @param commitAuthorCheck what to do when a commit sent through {@code CommitEntities} carries a
 *                          new stamp whose author is not the signed-in author
 */
@ConfigurationProperties("tinkar.auth")
public record AuthProperties(boolean enabled, String jwtSecret, Duration tokenTtl, Path credentialsFile,
                             CommitAuthorCheck commitAuthorCheck) {

    /** What to do about a commit whose new stamps name an author other than the signed-in one. */
    public enum CommitAuthorCheck {
        /** Store it without looking. */
        OFF,
        /** Store it, and log each stamp that names another author. */
        LOG,
        /** Refuse it, storing nothing. */
        REJECT
    }

    public AuthProperties {
        tokenTtl = tokenTtl == null ? Duration.ofHours(8) : tokenTtl;
        credentialsFile = credentialsFile == null ? Path.of("data", "credentials.properties") : credentialsFile;
        commitAuthorCheck = commitAuthorCheck == null ? CommitAuthorCheck.LOG : commitAuthorCheck;
    }
}
