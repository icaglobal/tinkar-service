package dev.ikm.tinkar.service.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/**
 * The authors' passwords, as bcrypt hashes in a file the service reads but never writes.
 *
 * <p>Each line is {@code <author UUID>=<bcrypt hash>}. {@code scripts/set-password.sh} writes
 * one with {@code htpasswd}, whose {@code $2y$} hashes are checked like {@code $2a$} ones. The
 * file is read at every sign-in, so a password set or removed takes effect without a restart.
 * It lives outside the knowledge base, so a password never travels in a changeset or export.
 */
public final class CredentialStore {

    private static final Logger LOG = LoggerFactory.getLogger(CredentialStore.class);

    private final Path file;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /**
     * A hash of a random password, checked against when an author has none, so that a sign-in as
     * an author without a password takes as long as a wrong password and does not tell them apart.
     */
    private final String noMatch = encoder.encode(UUID.randomUUID().toString());

    public CredentialStore(Path file) {
        this.file = file;
    }

    /**
     * Whether {@code password} is the author's.
     *
     * @param authorUuids the author's UUIDs; the file may key the author by any of them
     * @param password    the password given
     * @return false when it is not, when the author has no password, or when the file cannot be read
     */
    public boolean matches(List<UUID> authorUuids, String password) {
        String hash = hashOf(read(), authorUuids);
        if (password == null || password.isEmpty()) {
            return false;
        }
        if (hash == null) {
            encoder.matches(password, noMatch);
            return false;
        }
        return encoder.matches(password, hash.strip());
    }

    /**
     * Whether the author has a password here.
     *
     * @param authorUuids the author's UUIDs
     */
    public boolean hasPassword(List<UUID> authorUuids) {
        return hashOf(read(), authorUuids) != null;
    }

    private static String hashOf(Properties credentials, List<UUID> authorUuids) {
        for (UUID uuid : authorUuids) {
            String hash = credentials.getProperty(uuid.toString());
            if (hash != null) {
                return hash;
            }
        }
        return null;
    }

    /** The file this store reads. */
    public Path file() {
        return file;
    }

    private Properties read() {
        Properties credentials = new Properties();
        if (!Files.isRegularFile(file)) {
            LOG.warn("No credentials file at {}: no one can sign in", file.toAbsolutePath());
            return credentials;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            credentials.load(reader);
        } catch (IOException e) {
            LOG.error("Could not read the credentials file {}: {}", file.toAbsolutePath(), e.getMessage());
        }
        return credentials;
    }
}
