package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.EntityVersion;
import dev.ikm.tinkar.entity.RecordListBuilder;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.StampEntityVersion;
import dev.ikm.tinkar.entity.StampRecord;
import dev.ikm.tinkar.entity.StampVersionRecord;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.service.auth.AuthProperties;
import dev.ikm.tinkar.service.auth.AuthProperties.CommitAuthorCheck;
import dev.ikm.tinkar.service.auth.AuthRestController;
import dev.ikm.tinkar.service.auth.Authors;
import dev.ikm.tinkar.service.auth.CredentialStore;
import dev.ikm.tinkar.service.auth.TokenService;
import dev.ikm.tinkar.service.dto.AuthResponses.LoginRequest;
import dev.ikm.tinkar.service.dto.AuthResponses.LoginResponse;
import dev.ikm.tinkar.service.dto.ConceptCreationResponse;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.State;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Signing in as an author, against the IKE starter set: who can sign in, with what, and that the
 * service's writes are then stamped with the signed-in author.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignInTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private static final String SECRET = "a-secret-of-at-least-thirty-two-bytes-for-tests";

    /** {@code htpasswd -nbBC 10 x 'correct horse'}, as {@code scripts/set-password.sh} writes it. */
    private static final String HTPASSWD_HASH = "$2y$10$KRHhvqjIWap6gWiY.HwnIuVdHFWswzM4/SaSvYJ6XLDnHzft9dt06";

    private TinkarServiceImpl service;
    private File dataRoot;
    private Authors.Author author;
    private TokenService tokens;
    private AuthRestController signIn;

    @BeforeAll
    void startStore() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        dataRoot = Files.createTempDirectory("sign-in-test").toFile();
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, dataRoot);
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertThat(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount()).isPositive();

        TinkarPrimitiveImpl primitive = new TinkarPrimitiveImpl(dataRoot.getParent(), dataRoot.getName(), "Load Memory-Only 64-bit Store");
        service = new TinkarServiceImpl(primitive);

        author = Authors.list().getFirst();
        Path credentials = dataRoot.toPath().resolve("credentials.properties");
        Files.writeString(credentials, author.id() + "=" + HTPASSWD_HASH + "\n");
        tokens = new TokenService(SECRET, Duration.ofHours(1), Clock.systemUTC());
        signIn = new AuthRestController(
                new AuthProperties(true, SECRET, Duration.ofHours(1), credentials, CommitAuthorCheck.LOG),
                new CredentialStore(credentials), tokens);
    }

    @AfterAll
    void stopStoreAndRemoveItsDirectory() throws IOException {
        PrimitiveData.stop();
        try (Stream<Path> paths = Files.walk(dataRoot.toPath())) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
        service.setCommitAuthorCheck(CommitAuthorCheck.LOG);
    }

    // ── Who can sign in ──────────────────────────────────────────────────────

    @Test
    void theAuthorsAreListedByNameWithTheirIds() {
        List<Authors.Author> authors = Authors.list();

        assertThat(authors).isNotEmpty().allSatisfy(a -> {
            assertThat(a.name()).isNotBlank();
            assertThat(UUID.fromString(a.id())).isNotNull();
        });
        assertThat(authors.stream().map(Authors.Author::name).toList()).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void anAuthorIsFoundByNameIgnoringCaseOrByUuid() {
        assertThat(Authors.find(author.name().toUpperCase())).contains(author);
        assertThat(Authors.find(author.id())).contains(author);
        assertThat(Authors.find("no author is called this")).isEmpty();
        assertThat(Authors.find(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void theRightPasswordGivesATokenNamingTheAuthor() {
        ResponseEntity<LoginResponse> byName = signIn.login(new LoginRequest(author.name(), "correct horse"));
        ResponseEntity<LoginResponse> byUuid = signIn.login(new LoginRequest(author.id(), "correct horse"));

        for (ResponseEntity<LoginResponse> response : List.of(byName, byUuid)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getBody().success()).isTrue();
            assertThat(response.getBody().author().authorId()).isEqualTo(author.id());
            assertThat(tokens.check(response.getBody().token()).getSubject()).isEqualTo(author.id());
        }
        assertThat(signIn.status("Bearer " + byName.getBody().token()).signedInAs().name()).isEqualTo(author.name());
    }

    @Test
    void aWrongPasswordOrUnknownAuthorFailsTheSameWay() {
        ResponseEntity<LoginResponse> wrongPassword = signIn.login(new LoginRequest(author.name(), "wrong horse"));
        ResponseEntity<LoginResponse> unknownAuthor = signIn.login(new LoginRequest("nobody at all", "correct horse"));

        for (ResponseEntity<LoginResponse> response : List.of(wrongPassword, unknownAuthor)) {
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(response.getBody().token()).isNull();
            assertThat(response.getBody().errorMessage()).isEqualTo(wrongPassword.getBody().errorMessage());
        }
    }

    @Test
    void withAuthenticationOffNoOneSignsIn() {
        Path credentials = dataRoot.toPath().resolve("credentials.properties");
        AuthRestController off = new AuthRestController(
                new AuthProperties(false, null, null, credentials, null), new CredentialStore(credentials), tokens);

        assertThat(off.login(new LoginRequest(author.name(), "correct horse")).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(off.status(null).enabled()).isFalse();
    }

    // ── What a signed-in author's writes are stamped with ────────────────────

    @Test
    void aSignedInAuthorsWritesAreStampedWithTheAuthor() {
        signInAs(author);

        ConceptCreationResponse created = service.createConcept("Concept by the signed-in author",
                List.of(uuidOf(KernelConcept.ENGLISH_LANGUAGE)));

        assertThat(created.success()).as(created.errorMessage()).isTrue();
        assertThat(authorOf(created.conceptId())).isEqualTo(author.nid());
    }

    @Test
    void withNoOneSignedInWritesAreStampedWithTheGenericUser() {
        ConceptCreationResponse created = service.createConcept("Concept by no one in particular",
                List.of(uuidOf(KernelConcept.ENGLISH_LANGUAGE)));

        assertThat(created.success()).as(created.errorMessage()).isTrue();
        assertThat(authorOf(created.conceptId())).isEqualTo(KernelConcept.USER.nid());
    }

    // ── A commit's new stamps, against the signed-in author ──────────────────

    @Test
    void aCommitByAnotherAuthorIsRefusedOnlyWhenTheCheckRejects() {
        signInAs(author);
        List<StampEntity<StampEntityVersion>> byAnother = List.of(newStamp(KernelConcept.ENGLISH_LANGUAGE.nid()));

        service.setCommitAuthorCheck(CommitAuthorCheck.REJECT);
        assertThat(service.checkCommitAuthors(byAnother, "a commit")).contains("another author");

        service.setCommitAuthorCheck(CommitAuthorCheck.LOG);
        assertThat(service.checkCommitAuthors(byAnother, "a commit")).isNull();

        service.setCommitAuthorCheck(CommitAuthorCheck.OFF);
        assertThat(service.checkCommitAuthors(byAnother, "a commit")).isNull();
    }

    @Test
    void aCommitByTheSignedInAuthorIsAccepted() {
        signInAs(author);
        service.setCommitAuthorCheck(CommitAuthorCheck.REJECT);

        assertThat(service.checkCommitAuthors(List.of(newStamp(author.nid())), "a commit")).isNull();
    }

    @Test
    void withNoOneSignedInACommitIsNotChecked() {
        service.setCommitAuthorCheck(CommitAuthorCheck.REJECT);

        assertThat(service.checkCommitAuthors(List.of(newStamp(KernelConcept.ENGLISH_LANGUAGE.nid())), "a commit")).isNull();
    }

    @Test
    void aStampTheStoreAlreadyHoldsIsHistoryAndNotChecked() {
        signInAs(author);
        service.setCommitAuthorCheck(CommitAuthorCheck.REJECT);
        StampEntity<StampEntityVersion> held = newStamp(KernelConcept.ENGLISH_LANGUAGE.nid());
        EntityService.get().putEntity(held);

        assertThat(service.checkCommitAuthors(List.of(held), "a commit")).isNull();
    }

    private void signInAs(Authors.Author who) {
        String token = tokens.issue(who).token();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(tokens.check(token)));
    }

    /** The author of the stamp on the component's latest version. */
    private static long authorOf(String componentId) {
        long nid = EntityService.get().nidForPublicId(PublicIds.of(UUID.fromString(componentId)));
        Entity<? extends EntityVersion> entity = EntityHandle.get(nid).orNull();
        assertThat(entity).as("component " + componentId).isNotNull();
        return entity.versions().stream().map(EntityVersion::stamp)
                .max(Comparator.comparingLong(StampEntity::time)).orElseThrow().authorNid();
    }

    /** A committed stamp this store does not hold, by the given author. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static StampEntity<StampEntityVersion> newStamp(long authorNid) {
        UUID stampUuid = UUID.randomUUID();
        RecordListBuilder<StampVersionRecord> versions = RecordListBuilder.make();
        StampRecord stamp = new StampRecord(stampUuid.getMostSignificantBits(), stampUuid.getLeastSignificantBits(),
                null, EntityService.get().nidForStamp(PublicIds.of(stampUuid)), versions);   // a stamp's nid, never written
        versions.add(new StampVersionRecord(stamp, State.ACTIVE.nid(), System.currentTimeMillis(), authorNid,
                KernelConcept.PRIMORDIAL_MODULE.nid(), KernelConcept.DEVELOPMENT_PATH.nid()));
        versions.build();
        return (StampEntity) stamp;
    }

    private static String uuidOf(EntityFacade component) {
        return component.publicId().leastUuid().toString();
    }
}
