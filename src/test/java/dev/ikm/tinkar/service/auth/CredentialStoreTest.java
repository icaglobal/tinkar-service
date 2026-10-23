package dev.ikm.tinkar.service.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The authors' passwords, as {@code scripts/set-password.sh} writes them. */
class CredentialStoreTest {

    /** {@code htpasswd -nbBC 10 x 'correct horse'}: the {@code $2y$} form the script writes. */
    private static final String HTPASSWD_HASH = "$2y$10$KRHhvqjIWap6gWiY.HwnIuVdHFWswzM4/SaSvYJ6XLDnHzft9dt06";

    private static final UUID AUTHOR = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID ITS_OTHER_UUID = UUID.fromString("66666666-2222-3333-4444-555555555555");

    @TempDir
    Path dir;

    @Test
    void theRightPasswordMatchesAnHtpasswdHash() throws IOException {
        CredentialStore store = storeWith(AUTHOR + "=" + HTPASSWD_HASH);

        assertThat(store.matches(List.of(AUTHOR), "correct horse")).isTrue();
        assertThat(store.matches(List.of(AUTHOR), "Correct horse")).isFalse();
        assertThat(store.matches(List.of(AUTHOR), "")).isFalse();
        assertThat(store.matches(List.of(AUTHOR), null)).isFalse();
    }

    @Test
    void anAuthorIsFoundByAnyOfItsUuids() throws IOException {
        CredentialStore store = storeWith(AUTHOR + "=" + HTPASSWD_HASH);

        assertThat(store.matches(List.of(ITS_OTHER_UUID, AUTHOR), "correct horse")).isTrue();
        assertThat(store.hasPassword(List.of(ITS_OTHER_UUID, AUTHOR))).isTrue();
        assertThat(store.hasPassword(List.of(ITS_OTHER_UUID))).isFalse();
    }

    @Test
    void anAuthorWithNoPasswordCannotSignIn() throws IOException {
        CredentialStore store = storeWith(AUTHOR + "=" + HTPASSWD_HASH);

        assertThat(store.matches(List.of(ITS_OTHER_UUID), "correct horse")).isFalse();
        assertThat(store.matches(List.of(), "correct horse")).isFalse();
    }

    @Test
    void withNoFileNoOneCanSignIn() {
        CredentialStore store = new CredentialStore(dir.resolve("missing.properties"));

        assertThat(store.matches(List.of(AUTHOR), "correct horse")).isFalse();
    }

    @Test
    void aPasswordChangedInTheFileTakesEffectAtTheNextSignIn() throws IOException {
        CredentialStore store = storeWith(AUTHOR + "=" + HTPASSWD_HASH);
        assertThat(store.matches(List.of(AUTHOR), "correct horse")).isTrue();

        Files.writeString(store.file(), "# removed\n");

        assertThat(store.matches(List.of(AUTHOR), "correct horse")).isFalse();
    }

    private CredentialStore storeWith(String content) throws IOException {
        Path file = dir.resolve("credentials.properties");
        Files.writeString(file, content + "\n");
        return new CredentialStore(file);
    }
}
