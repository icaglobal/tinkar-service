package dev.ikm.tinkar.service.service.impl;

import network.ike.foundation.ike.bindings.IkeTerms;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.language.calculator.LanguageCalculator;
import dev.ikm.tinkar.entity.ConceptEntity;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityHandle;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.schema.StampVersion;
import dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.proto.TinkarSearchResult;
import org.assertj.core.api.SoftAssertions;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A component in a search, child, descendant, or entity response is described from the store:
 * its public id, its descriptions as the default view gives them, and the stamp of its version.
 *
 * <p>The tests run the service, with no part of it replaced, against the IKE starter set, so
 * each result is assembled through the same lookups a running service uses.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ComponentResultTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    private TinkarServiceImpl service;
    private TinkarPrimitiveImpl primitive;
    private File dataRoot;

    @BeforeAll
    void startStore() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        dataRoot = Files.createTempDirectory("component-result-test").toFile();
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, dataRoot);
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertThat(count).as("entities loaded from the starter data").isPositive();

        // The store is running, so the primitive layer attaches to it instead of opening one.
        primitive = new TinkarPrimitiveImpl(dataRoot.getParent(), dataRoot.getName(), "Load Ephemeral Store");
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
    void anEntityIsDescribedByItsNamesAndTheStampOfItsVersion() {
        ConceptEntity<?> active = EntityHandle.get(KernelTerm.ACTIVE_STATE).expectConcept();
        assertThat(active.versions())
                .as("precondition: one version, so the stamp the response reports is unambiguous")
                .hasSize(1);
        StampEntity stamp = EntityHandle.getStampOrThrow(active.versions().getFirst().stampNid());

        TinkarSearchQueryResponse response = service.getEntity(anyUuid(KernelTerm.ACTIVE_STATE.publicId()));

        assertThat(response.getSuccess()).as(response.getErrorMessage()).isTrue();
        assertThat(response.getResultsList()).hasSize(1);
        TinkarSearchResult result = response.getResults(0);
        SoftAssertions each = new SoftAssertions();
        each.assertThat(wireUuidStrings(result.getPublicId())).as("public id")
                .containsExactlyInAnyOrderElementsOf(uuidStrings(active.publicId()));
        assertNamedAsTheDefaultViewNamesIt(each, result, active.nid());
        StampVersion reported = result.getStamp();
        each.assertThat(reported.getTime()).as("stamp time").isEqualTo(stamp.time());
        // Each part of the stamp carries every UUID of its public id.
        each.assertThat(wireUuidStrings(reported.getStatusPublicId())).as("status")
                .containsExactlyInAnyOrderElementsOf(uuidStrings(EntityHandle.get(stamp.stateNid()).expectEntity().publicId()));
        each.assertThat(wireUuidStrings(reported.getAuthorPublicId())).as("author")
                .containsExactlyInAnyOrderElementsOf(uuidStrings(EntityHandle.get(stamp.authorNid()).expectEntity().publicId()));
        each.assertThat(wireUuidStrings(reported.getModulePublicId())).as("module")
                .containsExactlyInAnyOrderElementsOf(uuidStrings(EntityHandle.get(stamp.moduleNid()).expectEntity().publicId()));
        each.assertThat(wireUuidStrings(reported.getPathPublicId())).as("path")
                .containsExactlyInAnyOrderElementsOf(uuidStrings(EntityHandle.get(stamp.pathNid()).expectEntity().publicId()));
        each.assertAll();
    }

    @Test
    void eachChildIsDescribedFromTheStore() {
        List<PublicId> children = primitive.childrenOf(IkeTerms.STATUS_VALUE.publicId());
        assertThat(children).as("precondition: the status value concept has children").isNotEmpty();

        TinkarSearchQueryResponse response = service.getChildConcepts(anyUuid(IkeTerms.STATUS_VALUE.publicId()));

        assertDescribesEach(response, children);
    }

    @Test
    void eachDescendantIsDescribedFromTheStore() {
        List<PublicId> descendants = primitive.descendantsOf(IkeTerms.STATUS_VALUE.publicId());
        assertThat(descendants).as("precondition: the status value concept has descendants").isNotEmpty();

        TinkarSearchQueryResponse response =
                service.getDescendantConcepts(anyUuid(IkeTerms.STATUS_VALUE.publicId()));

        assertDescribesEach(response, descendants);
    }

    private void assertDescribesEach(TinkarSearchQueryResponse response, List<PublicId> expected) {
        assertThat(response.getSuccess()).as(response.getErrorMessage()).isTrue();
        assertThat(response.getResultsList()).hasSameSizeAs(expected);
        SoftAssertions each = new SoftAssertions();
        for (int i = 0; i < expected.size(); i++) {
            PublicId publicId = expected.get(i);
            TinkarSearchResult result = response.getResults(i);
            each.assertThat(wireUuidStrings(result.getPublicId())).as("public id of result " + i)
                    .containsExactlyInAnyOrderElementsOf(uuidStrings(publicId));
            Entity<?> entity = EntityHandle.get(publicId).expectEntity();
            assertNamedAsTheDefaultViewNamesIt(each, result, entity.nid());
            // The service reports the stamp of the entity's first version.
            StampEntity stamp = EntityHandle.getStampOrThrow(entity.versions().getFirst().stampNid());
            each.assertThat(result.getStamp().getTime()).as("stamp time of " + publicId).isEqualTo(stamp.time());
            each.assertThat(wireUuidStrings(result.getStamp().getStatusPublicId())).as("status of " + publicId)
                    .containsExactlyInAnyOrderElementsOf(uuidStrings(EntityHandle.get(stamp.stateNid()).expectEntity().publicId()));
        }
        each.assertAll();
    }

    private static void assertNamedAsTheDefaultViewNamesIt(SoftAssertions each, TinkarSearchResult result, long nid) {
        LanguageCalculator names = Calculators.View.Default().languageCalculator();
        String fullyQualifiedName = names.getFullyQualifiedNameText(nid).orElseThrow();
        each.assertThat(result.getDescriptions().getFullyQualifiedName()).as("fully qualified name")
                .isEqualTo(fullyQualifiedName);
        each.assertThat(result.getDescriptions().getRegularName()).as("regular name")
                .isEqualTo(names.getRegularDescriptionText(nid).orElse(""));
    }

    /** A request may name a component by any of its UUIDs; the least is as good as any. */
    private static String anyUuid(PublicId publicId) {
        return publicId.leastUuid().toString();
    }

    private static List<String> uuidStrings(PublicId publicId) {
        return publicId.asUuidList().stream().map(Object::toString).toList();
    }

    /**
     * A wire public id's UUIDs as text: the service writes each as two longs and none as text
     * (changeset format version 2), so a text UUID fails the comparison.
     */
    private static List<String> wireUuidStrings(dev.ikm.tinkar.schema.PublicId wire) {
        if (wire.getUuidsCount() > 0) {
            return List.of("UUIDs written as text: " + wire.getUuidsList());
        }
        return java.util.Arrays.stream(dev.ikm.tinkar.entity.changeset.SchemaIds.uuids(wire)).map(java.util.UUID::toString).toList();
    }
}
