package dev.ikm.tinkar.service.service.impl;

import java.util.OptionalLong;
import network.ike.foundation.ike.bindings.IkeTerms;
import dev.ikm.tinkar.terms.KernelTerm;
import dev.ikm.tinkar.common.id.PublicId;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.Coordinates;
import dev.ikm.tinkar.coordinate.stamp.StampCoordinateRecord;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.service.dto.ChangeHistoryResponse;
import dev.ikm.tinkar.service.dto.ConceptChangeHistoryResponse;
import dev.ikm.tinkar.service.dto.ConceptCreationResponse;
import dev.ikm.tinkar.service.dto.ConceptSemanticsResponse;
import dev.ikm.tinkar.service.dto.DescendantOperationResponse;
import dev.ikm.tinkar.service.dto.StampCoordinateDto;
import dev.ikm.tinkar.service.proto.TinkarConceptEntityResponse;
import dev.ikm.tinkar.service.proto.TinkarConceptSemanticsResponse;
import dev.ikm.tinkar.service.proto.TinkarSearchQueryResponse;
import dev.ikm.tinkar.service.proto.TinkarSemanticInfoResponse;
import dev.ikm.tinkar.service.service.CoordinateFactory;
import dev.ikm.tinkar.service.service.KnownComponents;
import dev.ikm.tinkar.service.service.UnknownComponentException;
import dev.ikm.tinkar.terms.EntityFacade;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * A request that names a UUID the knowledge base does not hold is refused, and the UUID is
 * assigned no nid ({@code IKE-Network/ike-issues#1188}).
 *
 * <p>The service used to ask the store for a nid for each id a request named. The in-memory
 * store these tests run against assigns a nid to a UUID it does not hold, as the spined-array
 * store the service uses by default does, so a request added to the store and was then answered
 * as though the component existed; the write endpoints wrote on a concept that does not exist.
 *
 * <p>The tests run the service, with no part of it replaced, against the IKE starter set.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class UnknownComponentRequestTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** What an endpoint answered: whether it succeeded, and its error message. */
    private record Outcome(boolean success, String message) {
    }

    private TinkarServiceImpl service;
    private TinkarPrimitiveImpl primitive;
    private File dataRoot;

    @BeforeAll
    void startStore() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        dataRoot = Files.createTempDirectory("unknown-component-request-test").toFile();
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

    // ── Every endpoint that takes an id ──────────────────────────────────────

    @Test
    void everyEndpointRefusesAUuidTheKnowledgeBaseDoesNotHoldAndAssignsItNoNid() {
        SoftAssertions each = new SoftAssertions();
        for (Map.Entry<String, Function<String, Outcome>> endpoint : endpoints().entrySet()) {
            UUID unknown = UUID.randomUUID();

            Outcome outcome = endpoint.getValue().apply(unknown.toString());

            each.assertThat(outcome.success()).as(endpoint.getKey() + ": refused").isFalse();
            each.assertThat(outcome.message()).as(endpoint.getKey() + ": the error names the UUID")
                    .isEqualTo("No component in this knowledge base has the public id " + unknown);
            each.assertThat(PrimitiveData.get().hasUuid(unknown))
                    .as(endpoint.getKey() + ": the UUID is assigned no nid")
                    .isFalse();
        }
        each.assertAll();
    }

    @Test
    void everyEndpointRefusesAUuidThatHasANidButNoEntity() {
        // A public id gets a nid when another component refers to it, whether or not the
        // component it names was ever written. That is not enough to answer a request about it.
        SoftAssertions each = new SoftAssertions();
        for (Map.Entry<String, Function<String, Outcome>> endpoint : endpoints().entrySet()) {
            UUID referredToOnly = UUID.randomUUID();
            PrimitiveData.nid(PublicIds.of(referredToOnly));

            Outcome outcome = endpoint.getValue().apply(referredToOnly.toString());

            each.assertThat(outcome.success()).as(endpoint.getKey() + ": refused").isFalse();
            each.assertThat(outcome.message()).as(endpoint.getKey())
                    .isEqualTo("No component in this knowledge base has the public id " + referredToOnly);
        }
        each.assertAll();
    }

    @Test
    void everyEndpointRefusesTextThatIsNotAUuid() {
        SoftAssertions each = new SoftAssertions();
        for (Map.Entry<String, Function<String, Outcome>> endpoint : endpoints().entrySet()) {
            Outcome outcome = endpoint.getValue().apply("not-a-uuid");

            each.assertThat(outcome.success()).as(endpoint.getKey() + ": refused").isFalse();
            each.assertThat(outcome.message()).as(endpoint.getKey()).contains("not-a-uuid");
        }
        each.assertAll();
    }

    @Test
    void theWriteEndpointsWriteNothingForAnIdTheKnowledgeBaseDoesNotHold() {
        String known = uuidOf(KernelTerm.ENGLISH_LANGUAGE);
        int conceptsBefore = conceptCount();
        int semanticsBefore = semanticCount();

        service.createSampleChange(UUID.randomUUID().toString(), "a comment on nothing");
        service.addDescendant(UUID.randomUUID().toString(), known);
        service.addDescendant(known, UUID.randomUUID().toString());
        service.createAndAddDescendant(UUID.randomUUID().toString(), "A child of nothing");
        service.removeDescendant(UUID.randomUUID().toString(), known);
        service.removeDescendant(known, UUID.randomUUID().toString());
        service.createConcept("A concept defined by nothing", List.of(UUID.randomUUID().toString()));

        assertThat(conceptCount()).as("concepts in the store").isEqualTo(conceptsBefore);
        assertThat(semanticCount()).as("semantics in the store").isEqualTo(semanticsBefore);
    }

    @Test
    void aComponentTheKnowledgeBaseHoldsIsAnsweredAsBefore() {
        String known = uuidOf(KernelTerm.ENGLISH_LANGUAGE);
        SoftAssertions each = new SoftAssertions();
        // The endpoints that read. A write would change the store under the other tests.
        for (String name : List.of("entity", "children", "descendants", "children under a view",
                "descendants under a view", "change history", "comments", "semantics",
                "semantics as a gRPC message", "entity graph", "entity by id", "concept change history")) {
            Outcome outcome = endpoints().get(name).apply(known);
            each.assertThat(outcome.success()).as(name + ": " + outcome.message()).isTrue();
        }
        each.assertAll();
    }

    // ── The conversion every handler goes through ────────────────────────────

    @Test
    void theIdOfARequestIsConvertedOnlyWhenTheKnowledgeBaseHoldsTheComponent() {
        UUID unknown = UUID.randomUUID();

        PublicId known = primitive.getPublicId(uuidOf(KernelTerm.ENGLISH_LANGUAGE));
        assertThat(PublicId.equals(known, KernelTerm.ENGLISH_LANGUAGE.publicId())).isTrue();

        assertThatThrownBy(() -> primitive.getPublicId(unknown.toString()))
                .isInstanceOf(UnknownComponentException.class)
                .hasMessage("No component in this knowledge base has the public id " + unknown);
        assertThatThrownBy(() -> primitive.getPublicId("not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(PrimitiveData.get().hasUuid(unknown)).isFalse();
    }

    @Test
    void aLookupAssignsNothingAndWantsAnEntity() {
        UUID unknown = UUID.randomUUID();
        UUID referredToOnly = UUID.randomUUID();
        long assigned = PrimitiveData.nid(PublicIds.of(referredToOnly));

        assertThat(KnownComponents.nid(KernelTerm.ENGLISH_LANGUAGE.publicId()))
                .isEqualTo(OptionalLong.of(KernelTerm.ENGLISH_LANGUAGE.nid()));
        assertThat(KnownComponents.nid(PublicIds.of(unknown))).isEmpty();
        assertThat(PrimitiveData.get().hasUuid(unknown)).isFalse();
        assertThat(KnownComponents.nid(PublicIds.of(referredToOnly)))
                .as("a nid with no entity behind it: " + assigned)
                .isEmpty();
        assertThatThrownBy(() -> KnownComponents.nidOrRefuse(PublicIds.of(unknown)))
                .isInstanceOf(UnknownComponentException.class);
    }

    // ── The path and modules of a coordinate in a request ────────────────────

    @Test
    void aCoordinateWithAnUnknownPathOrModuleAssignsNoNid() {
        UUID path = UUID.randomUUID();
        UUID module = UUID.randomUUID();
        UUID excluded = UUID.randomUUID();
        UUID priority = UUID.randomUUID();

        StampCoordinateRecord coordinate = CoordinateFactory.buildStampCoordinate(new StampCoordinateDto(
                null, null, path.toString(),
                List.of(module.toString()), List.of(excluded.toString()), List.of(priority.toString())));

        for (UUID unknown : List.of(path, module, excluded, priority)) {
            assertThat(PrimitiveData.get().hasUuid(unknown)).as("a nid for " + unknown).isFalse();
        }
        // What happens to an unknown id is as it was written: the development path is used, and
        // the module is left out.
        assertThat(coordinate.stampPosition().getPathForPositionNid())
                .isEqualTo(Coordinates.Stamp.DevelopmentLatest().stampPosition().getPathForPositionNid());
        assertThat(coordinate.moduleNids().size()).isZero();
        assertThat(coordinate.excludedModuleNids().size()).isZero();
        assertThat(coordinate.modulePriorityNidList().size()).isZero();
    }

    @Test
    void aCoordinateWithAPathAndModulesTheKnowledgeBaseHoldsUsesThem() {
        String module = uuidOf(IkeTerms.DEVELOPMENT_MODULE);

        StampCoordinateRecord coordinate = CoordinateFactory.buildStampCoordinate(new StampCoordinateDto(
                null, null, uuidOf(KernelTerm.DEVELOPMENT_PATH), List.of(module), List.of(), List.of(module)));

        assertThat(coordinate.stampPosition().getPathForPositionNid()).isEqualTo(KernelTerm.DEVELOPMENT_PATH.nid());
        assertThat(coordinate.moduleNids().toArray()).containsExactly(IkeTerms.DEVELOPMENT_MODULE.nid());
        assertThat(coordinate.modulePriorityNidList().toArray()).containsExactly(IkeTerms.DEVELOPMENT_MODULE.nid());
    }

    // ── The endpoints ────────────────────────────────────────────────────────

    /**
     * Every endpoint of the service that takes the id of a component, by a name for the failure
     * message. Endpoints that take two ids are listed once for each.
     */
    private Map<String, Function<String, Outcome>> endpoints() {
        String known = uuidOf(KernelTerm.ENGLISH_LANGUAGE);
        Map<String, Function<String, Outcome>> endpoints = new LinkedHashMap<>();
        endpoints.put("entity", id -> outcome(service.getEntity(id)));
        endpoints.put("children", id -> outcome(service.getChildConcepts(id)));
        endpoints.put("descendants", id -> outcome(service.getDescendantConcepts(id)));
        endpoints.put("children under a view", id -> outcome(service.getChildConcepts(id, null)));
        endpoints.put("descendants under a view", id -> outcome(service.getDescendantConcepts(id, null)));
        endpoints.put("LIDR records", id -> outcome(service.getLIDRRecordConceptsFromTestKit(id)));
        endpoints.put("result conformances", id -> outcome(service.getResultConformanceConceptsFromLIDRRecord(id)));
        endpoints.put("allowed results", id -> outcome(service.getAllowedResultConceptsFromResultConformance(id)));
        endpoints.put("change history", id -> {
            ChangeHistoryResponse response = service.getChangeHistory(id);
            return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
        });
        endpoints.put("add a comment", id -> {
            ChangeHistoryResponse response = service.createSampleChange(id, "a comment");
            return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
        });
        endpoints.put("comments", id -> {
            ConceptSemanticsResponse response = service.getConceptComments(id);
            return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
        });
        endpoints.put("semantics", id -> {
            ConceptSemanticsResponse response = service.inspectConcept(id);
            return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
        });
        endpoints.put("semantics as a gRPC message", id -> {
            TinkarConceptSemanticsResponse response = service.inspectConceptProto(id);
            return new Outcome(response.getSuccess(), response.getErrorMessage());
        });
        endpoints.put("one semantic", id -> {
            TinkarSemanticInfoResponse response = service.getSemanticInfo(id);
            return new Outcome(response.getSuccess(), response.getErrorMessage());
        });
        endpoints.put("entity graph", id -> {
            TinkarConceptEntityResponse response = service.loadConceptEntityGraph(id);
            return new Outcome(response.getSuccess(), response.getErrorMessage());
        });
        endpoints.put("entity by id", id -> {
            TinkarConceptEntityResponse response = service.getEntityByPublicId(id);
            return new Outcome(response.getSuccess(), response.getErrorMessage());
        });
        endpoints.put("concept change history", id -> {
            ConceptChangeHistoryResponse response = service.getConceptChangeHistory(id);
            return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
        });
        endpoints.put("add a descendant, as the parent", id -> outcome(service.addDescendant(id, known)));
        endpoints.put("add a descendant, as the descendant", id -> outcome(service.addDescendant(known, id)));
        endpoints.put("create a descendant, as the parent",
                id -> outcome(service.createAndAddDescendant(id, "A new concept")));
        endpoints.put("remove a descendant, as the parent", id -> outcome(service.removeDescendant(id, known)));
        endpoints.put("remove a descendant, as the descendant", id -> outcome(service.removeDescendant(known, id)));
        endpoints.put("create a concept, as a parent", id -> {
            ConceptCreationResponse response = service.createConcept("A new concept", List.of(id));
            return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
        });
        return endpoints;
    }

    private static Outcome outcome(TinkarSearchQueryResponse response) {
        return new Outcome(response.getSuccess(), response.getErrorMessage());
    }

    private static Outcome outcome(DescendantOperationResponse response) {
        return new Outcome(Boolean.TRUE.equals(response.success()), response.errorMessage());
    }

    /** The UUID a request names a component by: any of them would do; the least, as the service writes it. */
    private static String uuidOf(EntityFacade component) {
        return component.publicId().leastUuid().toString();
    }

    private static int conceptCount() {
        AtomicInteger count = new AtomicInteger();
        EntityService.get().forEachConceptEntity(concept -> count.incrementAndGet());
        return count.get();
    }

    private static int semanticCount() {
        AtomicInteger count = new AtomicInteger();
        EntityService.get().forEachSemanticEntity(semantic -> count.incrementAndGet());
        return count.get();
    }
}
