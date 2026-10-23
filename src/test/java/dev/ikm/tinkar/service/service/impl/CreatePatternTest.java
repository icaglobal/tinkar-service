package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.service.auth.Authors;
import dev.ikm.tinkar.service.auth.TokenService;
import dev.ikm.tinkar.service.dto.CreateConceptRequest;
import dev.ikm.tinkar.service.dto.CreateConceptRequest.DescriptionType;
import dev.ikm.tinkar.service.dto.PatternCreation;
import dev.ikm.tinkar.service.dto.PatternCreation.CreatePatternRequest;
import dev.ikm.tinkar.service.dto.PatternCreation.Field;
import dev.ikm.tinkar.service.dto.PatternCreation.PatternCreationResponse;
import dev.ikm.tinkar.service.dto.PatternsResponse;
import dev.ikm.tinkar.service.service.CoordinateFactory;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.KernelConcept;
import dev.ikm.tinkar.terms.KernelPattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
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
 * Creating a pattern through the service, against the IKE starter set: what is written, and what
 * is refused with nothing written.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CreatePatternTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private TinkarServiceImpl service;
    private File dataRoot;
    private String stringType;
    private String componentType;

    @BeforeAll
    void startStore() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        dataRoot = Files.createTempDirectory("create-pattern-test").toFile();
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, dataRoot);
        PrimitiveData.selectControllerByName("Load Memory-Only 64-bit Store");
        PrimitiveData.start();
        assertThat(new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount()).isPositive();

        TinkarPrimitiveImpl primitive = new TinkarPrimitiveImpl(dataRoot.getParent(), dataRoot.getName(),
                "Load Memory-Only 64-bit Store");
        service = new TinkarServiceImpl(primitive);
        stringType = dataTypeNamed("String display field");
        componentType = dataTypeNamed("Component display field");
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
    }

    @Test
    void theFieldDataTypesAreTheOnesKometOffers() {
        List<String> names = service.fieldDataTypes().dataTypes().stream().map(PatternCreation.Choice::name).toList();

        assertThat(names).contains("String display field", "Component display field", "Integer display field");
        assertThat(names).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void aPatternIsWrittenWithItsNamesMeaningPurposeAndFieldsInOrder() {
        PatternCreationResponse response = service.createPattern(new CreatePatternRequest(
                List.of(name("Device material pattern", DescriptionType.FULLY_QUALIFIED_NAME),
                        name("Device material", DescriptionType.REGULAR_NAME)),
                uuidOf(KernelConcept.ENGLISH_LANGUAGE),
                uuidOf(KernelConcept.DESCRIPTION_TYPE),
                List.of(new Field(uuidOf(KernelConcept.ENGLISH_LANGUAGE), uuidOf(KernelConcept.ENGLISH_LANGUAGE), stringType),
                        new Field(uuidOf(KernelConcept.DESCRIPTION_TYPE), uuidOf(KernelConcept.DESCRIPTION_TYPE), componentType))));

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        PatternsResponse.PatternSummary pattern = response.pattern();
        assertThat(pattern.name()).isIn("Device material pattern", "Device material");
        assertThat(pattern.meaning()).isEqualTo(nameOf(KernelConcept.ENGLISH_LANGUAGE));
        assertThat(pattern.purpose()).isEqualTo(nameOf(KernelConcept.DESCRIPTION_TYPE));
        assertThat(pattern.fields()).extracting(PatternsResponse.FieldDefinition::dataType)
                .containsExactly("String display field", "Component display field");
        assertThat(pattern.fields()).extracting(PatternsResponse.FieldDefinition::index).containsExactly(0, 1);
        assertThat(pattern.stamp().status()).isEqualTo("Active");
        assertThat(pattern.stamp().author()).isEqualTo(nameOf(KernelConcept.USER));

        assertThat(service.patterns().patterns()).extracting(PatternsResponse.PatternSummary::patternId)
                .contains(pattern.patternId());
    }

    @Test
    void aPatternMayHaveNoFields() {
        PatternCreationResponse response = service.createPattern(new CreatePatternRequest(
                List.of(name("Membership tag pattern", DescriptionType.FULLY_QUALIFIED_NAME)),
                uuidOf(KernelConcept.ENGLISH_LANGUAGE), uuidOf(KernelConcept.ENGLISH_LANGUAGE), List.of()));

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        assertThat(response.pattern().fields()).isEmpty();
    }

    @Test
    void aSignedInAuthorsPatternIsStampedWithTheAuthor() {
        Authors.Author author = Authors.list().getFirst();
        TokenService tokens = new TokenService("a-secret-of-at-least-thirty-two-bytes-for-tests", Duration.ofHours(1),
                Clock.systemUTC());
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(tokens.check(tokens.issue(author).token())));

        PatternCreationResponse response = service.createPattern(new CreatePatternRequest(
                List.of(name("Signed-in author's pattern", DescriptionType.FULLY_QUALIFIED_NAME)),
                uuidOf(KernelConcept.ENGLISH_LANGUAGE), uuidOf(KernelConcept.ENGLISH_LANGUAGE),
                List.of(new Field(uuidOf(KernelConcept.ENGLISH_LANGUAGE), uuidOf(KernelConcept.ENGLISH_LANGUAGE), stringType))));

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        assertThat(response.pattern().stamp().author()).isEqualTo(author.name());
    }

    @Test
    void whatIsRefusedLeavesNothingBehind() {
        int before = service.patterns().patterns().size();
        String concept = uuidOf(KernelConcept.ENGLISH_LANGUAGE);
        List<CreateConceptRequest.Description> names = List.of(name("Refused pattern", DescriptionType.FULLY_QUALIFIED_NAME));

        assertRefused(new CreatePatternRequest(List.of(name("No name", DescriptionType.REGULAR_NAME)), concept, concept, List.of()),
                "FULLY_QUALIFIED_NAME");
        assertRefused(new CreatePatternRequest(names, null, concept, List.of()), "The meaning is required");
        assertRefused(new CreatePatternRequest(names, "not-a-uuid", concept, List.of()), "The meaning is not a UUID");
        UUID unknown = UUID.randomUUID();
        assertRefused(new CreatePatternRequest(names, concept, unknown.toString(), List.of()), unknown.toString());
        assertRefused(new CreatePatternRequest(names, uuidOf(KernelPattern.DESCRIPTION_PATTERN), concept, List.of()),
                "is a pattern, not a concept");
        assertRefused(new CreatePatternRequest(names, concept, concept, List.of(new Field(concept, concept, concept))),
                "Field 1's data type");
        assertRefused(new CreatePatternRequest(names, concept, concept, List.of(new Field(concept, null, stringType))),
                "Field 1's purpose is required");

        assertThat(service.patterns().patterns()).hasSize(before);
    }

    private void assertRefused(CreatePatternRequest request, String reason) {
        PatternCreationResponse response = service.createPattern(request);
        assertThat(response.success()).isFalse();
        assertThat(response.errorMessage()).contains(reason);
    }

    private String dataTypeNamed(String name) {
        return service.fieldDataTypes().dataTypes().stream()
                .filter(choice -> choice.name().equals(name))
                .findFirst().orElseThrow(() -> new AssertionError("no data type named " + name))
                .id();
    }

    /** The name the service writes for a component: its regular name, else its fully qualified name. */
    private static String nameOf(EntityFacade component) {
        var language = CoordinateFactory.defaultCalculator().languageCalculator();
        return language.getRegularDescriptionText(component.nid())
                .or(() -> language.getFullyQualifiedNameText(component.nid()))
                .orElseThrow();
    }

    private static CreateConceptRequest.Description name(String text, DescriptionType type) {
        return new CreateConceptRequest.Description(text, type, null, null);
    }

    private static String uuidOf(EntityFacade component) {
        return component.publicId().leastUuid().toString();
    }
}
