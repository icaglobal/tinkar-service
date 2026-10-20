package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.changeset.SchemaIds;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.service.dto.PatternMembersResponse;
import dev.ikm.tinkar.service.dto.PatternsResponse;
import dev.ikm.tinkar.service.proto.ListPatternMembersResponse;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The REST answers for patterns: every pattern with its definition, and a pattern's members a
 * page at a time, the same members the gRPC answer selects.
 *
 * <p>The tests run the service, with no part of it replaced, against the IKE starter set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PatternsJsonTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private TinkarServiceImpl service;
    private File dataRoot;

    @BeforeAll
    void startStore() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        dataRoot = Files.createTempDirectory("patterns-json-test").toFile();
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, dataRoot);
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertThat(count).as("entities loaded from the starter data").isPositive();

        // The store is running, so the primitive layer attaches to it instead of opening one.
        TinkarPrimitiveImpl primitive = new TinkarPrimitiveImpl(dataRoot.getParent(), dataRoot.getName(), "Load Ephemeral Store");
        service = new TinkarServiceImpl(primitive);
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

    @Test
    void everyPatternIsListedByNameWithItsDefinition() {
        PatternsResponse response = service.patterns();

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        List<String> names = response.patterns().stream().map(PatternsResponse.PatternSummary::name).toList();
        assertThat(names).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);

        PatternsResponse.PatternSummary description = response.patterns().stream()
                .filter(pattern -> pattern.patternId().equals(uuidOf(KernelPattern.DESCRIPTION_PATTERN)))
                .findFirst().orElseThrow();
        assertThat(description.name()).isNotBlank();
        assertThat(description.meaning()).isNotBlank();
        assertThat(description.purpose()).isNotBlank();
        assertThat(description.fields()).as("the description pattern's fields").isNotEmpty();
        assertThat(description.fields()).allSatisfy(field -> {
            assertThat(field.meaning()).isNotBlank();
            assertThat(field.dataType()).isNotBlank();
        });
        assertThat(description.stamp().status()).isNotBlank();
    }

    @Test
    void aPageOfMembersNamesEachFieldAndReferencedComponent() {
        String pattern = uuidOf(KernelPattern.DESCRIPTION_PATTERN);

        PatternMembersResponse page = service.patternMembers(pattern, 0, 5, null, null);

        assertThat(page.success()).as(page.errorMessage()).isTrue();
        assertThat(page.members()).hasSize(5);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.totalCount()).isGreaterThan(5);
        int fieldCount = service.patterns().patterns().stream()
                .filter(summary -> summary.patternId().equals(pattern))
                .findFirst().orElseThrow().fields().size();
        assertThat(page.members()).allSatisfy(member -> {
            assertThat(member.semanticId()).isNotBlank();
            assertThat(member.referencedComponentId()).isNotBlank();
            assertThat(member.referencedComponentName()).isNotBlank();
            assertThat(member.fields()).hasSize(fieldCount);
            assertThat(member.fields()).allSatisfy(field -> assertThat(field.name()).isNotBlank());
        });
    }

    @Test
    void theRestPageHoldsTheMembersTheGrpcPageSelects() {
        String pattern = uuidOf(KernelPattern.DESCRIPTION_PATTERN);

        PatternMembersResponse rest = service.patternMembers(pattern, 3, 7, null, null);
        ListPatternMembersResponse grpc = service.listPatternMembers(pattern, 3, 7, null, null);

        assertThat(rest.members().stream().map(PatternMembersResponse.Member::semanticId).toList())
                .isEqualTo(grpc.getMemberIdsList().stream()
                        .map(id -> SchemaIds.toPublicId(id).leastUuid().toString())
                        .toList());
        assertThat(rest.totalCount()).isEqualTo(grpc.getTotalCount());
        assertThat(rest.hasMore()).isEqualTo(grpc.getHasMore());
    }

    @Test
    void membersCanBeFilteredByReferencedComponent() {
        String pattern = uuidOf(KernelPattern.DESCRIPTION_PATTERN);
        String english = uuidOf(KernelConcept.ENGLISH_LANGUAGE);

        PatternMembersResponse byId = service.patternMembers(pattern, 0, 50, null, english);
        assertThat(byId.success()).as(byId.errorMessage()).isTrue();
        assertThat(byId.members()).isNotEmpty()
                .allSatisfy(member -> assertThat(member.referencedComponentId()).isEqualTo(english));

        PatternMembersResponse byText = service.patternMembers(pattern, 0, 50, "english language", null);
        assertThat(byText.success()).as(byText.errorMessage()).isTrue();
        assertThat(byText.members()).isNotEmpty()
                .allSatisfy(member -> assertThat(member.referencedComponentName()).containsIgnoringCase("english language"));
    }

    @Test
    void anUnknownPatternIsRefusedAndAComponentThatIsNotOneIsNotFound() {
        UUID unknown = UUID.randomUUID();
        PatternMembersResponse refused = service.patternMembers(unknown.toString(), 0, 10, null, null);
        assertThat(refused.success()).isFalse();
        assertThat(refused.errorMessage()).isEqualTo("No component in this knowledge base has the public id " + unknown);

        String concept = uuidOf(KernelConcept.ENGLISH_LANGUAGE);
        PatternMembersResponse notAPattern = service.patternMembers(concept, 0, 10, null, null);
        assertThat(notAPattern.success()).isFalse();
        assertThat(notAPattern.errorMessage()).isEqualTo("Pattern not found: " + concept);
    }

    /** The UUID a request names a component by: the least, as the service writes it. */
    private static String uuidOf(EntityFacade component) {
        return component.publicId().leastUuid().toString();
    }
}
