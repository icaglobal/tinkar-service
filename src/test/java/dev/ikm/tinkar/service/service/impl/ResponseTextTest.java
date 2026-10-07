package dev.ikm.tinkar.service.service.impl;

import dev.ikm.tinkar.terms.KernelTerm;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ikm.tinkar.common.id.IntIdSet;
import dev.ikm.tinkar.common.id.IntIds;
import dev.ikm.tinkar.common.id.PublicIds;
import dev.ikm.tinkar.common.service.CachingService;
import dev.ikm.tinkar.common.service.PrimitiveData;
import dev.ikm.tinkar.common.service.ServiceKeys;
import dev.ikm.tinkar.common.service.ServiceProperties;
import dev.ikm.tinkar.coordinate.Calculators;
import dev.ikm.tinkar.coordinate.language.calculator.LanguageCalculator;
import dev.ikm.tinkar.coordinate.stamp.calculator.Latest;
import dev.ikm.tinkar.coordinate.view.calculator.ViewCalculatorWithCache;
import dev.ikm.tinkar.entity.ConceptRecord;
import dev.ikm.tinkar.entity.Entity;
import dev.ikm.tinkar.entity.EntityService;
import dev.ikm.tinkar.entity.SemanticRecord;
import dev.ikm.tinkar.entity.StampEntity;
import dev.ikm.tinkar.entity.StampEntityVersion;
import dev.ikm.tinkar.entity.graph.DiTreeEntity;
import dev.ikm.tinkar.entity.graph.DiTreeText;
import dev.ikm.tinkar.entity.graph.EntityVertex;
import dev.ikm.tinkar.entity.load.LoadEntitiesFromProtobufFile;
import dev.ikm.tinkar.entity.transaction.Transaction;
import dev.ikm.tinkar.service.dto.ChangeHistoryResponse;
import dev.ikm.tinkar.service.dto.ConceptChangeHistoryResponse;
import dev.ikm.tinkar.service.dto.ConceptSearchResponse;
import dev.ikm.tinkar.service.dto.ConceptSemanticsResponse;
import dev.ikm.tinkar.service.dto.SearchSortOption;
import dev.ikm.tinkar.service.proto.TinkarConceptSemanticInfo;
import dev.ikm.tinkar.service.proto.TinkarConceptSemanticsResponse;
import dev.ikm.tinkar.service.proto.TinkarSemanticField;
import dev.ikm.tinkar.service.proto.TinkarSemanticInfoResponse;
import dev.ikm.tinkar.service.util.ProtoConversionUtils;
import dev.ikm.tinkar.terms.EntityFacade;
import dev.ikm.tinkar.terms.EntityProxy;
import dev.ikm.tinkar.terms.State;
import org.eclipse.collections.api.factory.Lists;
import org.eclipse.collections.api.factory.primitive.IntObjectMaps;
import org.eclipse.collections.api.list.ImmutableList;
import org.eclipse.collections.api.map.primitive.MutableIntObjectMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/**
 * The text of the service's responses holds no nid ({@code IKE-Network/ike-issues#1177}).
 *
 * <p>A nid is local to the server's store. The caller holds another store, or none, and in gRPC
 * mode the text of a response is what the assistant's tools return, so it is kept. A component
 * is therefore identified in text by its public id only: a component with no description is
 * named by its first UUID, and a component the store has no public id for is written as
 * {@code unidentified component}.
 *
 * <p>The tests run the service, with no part of it replaced, against the IKE starter set in
 * an ephemeral store. Every concept of the starter data has a description, so the fallbacks are
 * reached with components written here: a concept with no description, and semantics that hold
 * it in a component field, in an id set, and in a definition tree.
 *
 * <p>The ephemeral store numbers components upward from {@code Integer.MIN_VALUE + 1}, so in
 * decimal every nid it assigns is a minus sign and ten digits beginning {@code 21474} or
 * {@code 21473}. {@link #assertNoNid} looks for a number of that shape and for the forms a nid
 * has been written in: {@code <nid>}, {@code nid: N}, and {@code [nid N]}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResponseTextTest {

    private static final File PB_STARTER_DATA = new File("target/data/ike-starter-set-reasoned-pb.zip");

    /** A nid of the ephemeral store in decimal: {@code Integer.MIN_VALUE} plus a small count. */
    private static final Pattern EPHEMERAL_NID = Pattern.compile("-2147[34]\\d{5}(?!\\d)");

    /** The form {@code PrimitiveData.text} writes for a component with no description. */
    private static final Pattern ANGLE_BRACKET_NID = Pattern.compile("<-?\\d+>");

    /** A number labelled as a nid: {@code nid=N}, {@code nid N}, {@code nid: N}. */
    private static final Pattern LABELLED_NID = Pattern.compile("(?i)\\bnid\\b\\s*[=:]?\\s*-?\\d+");

    /**
     * A nid the store has assigned to no component, so it has no public id for it: the store
     * numbers components upward from the bottom of the int range and never reaches the top.
     */
    private static final int UNASSIGNED_NID = Integer.MAX_VALUE - 1;

    /** A word no description of the starter data holds; a comment written here does. */
    private static final String SEARCH_WORD = "quaggamarker";

    private static final String HOLDER_NAME = "Holder of the response text test semantics";

    /** A concept with no description. */
    private static final UUID UNDESCRIBED = fixtureUuid("undescribed concept");
    /** A described concept that the semantics under test are attached to. */
    private static final UUID HOLDER = fixtureUuid("holder concept");
    /** On the holder: an identifier whose source is the undescribed concept. */
    private static final UUID IDENTIFIER = fixtureUuid("identifier semantic");
    /** On the holder: an id set of a described concept, the undescribed concept, and an unassigned nid. */
    private static final UUID ID_SET = fixtureUuid("id set semantic");
    /** On the holder: a definition tree that refers to the undescribed concept. */
    private static final UUID DEFINITION = fixtureUuid("definition semantic");
    /** On the undescribed concept: a semantic with no text in it and nothing attached to it. */
    private static final UUID BARE = fixtureUuid("bare semantic");
    /** On the undescribed concept: a semantic with no text in it, which the comment is attached to. */
    private static final UUID COMMENTED = fixtureUuid("commented semantic");
    /** On the commented semantic: a comment, the only text anywhere under the undescribed concept. */
    private static final UUID COMMENT = fixtureUuid("comment semantic");

    private TinkarServiceImpl service;
    private File dataRoot;
    private int undescribedNid;
    private int holderNid;
    private int commentNid;

    @BeforeAll
    void startStoreAndWriteTheComponentsUnderTest() throws IOException {
        assertThat(PB_STARTER_DATA)
                .as("the starter data, copied by maven-dependency-plugin in process-test-resources")
                .exists();
        // A new directory each run: the search index is kept under it, and one left by an
        // earlier run would answer a search with that run's entries as well.
        dataRoot = Files.createTempDirectory("response-text-test").toFile();
        CachingService.clearAll();
        ServiceProperties.set(ServiceKeys.DATA_STORE_ROOT, dataRoot);
        PrimitiveData.selectControllerByName("Load Ephemeral Store");
        PrimitiveData.start();
        long count = new LoadEntitiesFromProtobufFile(PB_STARTER_DATA).compute().getTotalCount();
        assertThat(count).as("entities loaded from the starter data").isPositive();

        writeTheComponentsUnderTest();

        // The store is running, so the primitive layer attaches to it instead of opening one.
        service = new TinkarServiceImpl(
                new TinkarPrimitiveImpl(dataRoot.getParent(), dataRoot.getName(), "Load Ephemeral Store"));
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

    // ── Components with descriptions ─────────────────────────────────────────

    @Test
    void theSemanticsOfADescribedConceptHoldNoNid() {
        TinkarConceptSemanticsResponse response = service.inspectConceptProto(uuidOf(KernelTerm.ENGLISH_LANGUAGE));

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getConceptDescription()).isEqualTo(name(KernelTerm.ENGLISH_LANGUAGE));
        assertThat(response.getSemanticsCount()).isPositive();
        assertNoNid("the semantics of a described concept", response.toString());
    }

    @Test
    void aDefinitionIsWrittenAsATreeOfNames() {
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        ViewCalculatorWithCache view = Calculators.View.Default();
        Latest<DiTreeEntity> stated = view.logicCalculator().getStatedLogicalExpressionForEntity(nid, view.stampCalculator());
        assertThat(stated.isPresent()).as("the starter data defines the concept").isTrue();

        TinkarConceptSemanticsResponse response = service.inspectConceptProto(uuidOf(KernelTerm.ENGLISH_LANGUAGE));
        String definition = onlyFieldOf(semanticOfPattern(response, KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN));

        assertThat(definition)
                .as("the layout the assistant writes for a local store: a line break, then the tree")
                .isEqualTo('\n' + DiTreeText.tree(stated.get(), ResponseTextTest::name))
                .startsWith("\n   [0]")
                .doesNotContain("DiTreeEntity{");
        assertThat(definition.lines().filter(line -> line.stripLeading().startsWith("[")).count())
                .as("one line per vertex")
                .isEqualTo(stated.get().vertexCount());
        assertNoNid("the definition", definition);
    }

    // ── A concept with no description ────────────────────────────────────────

    @Test
    void aConceptWithNoDescriptionIsNamedByItsUuid() {
        TinkarConceptSemanticsResponse response = service.inspectConceptProto(UNDESCRIBED.toString());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getConceptDescription()).isEqualTo(UNDESCRIBED.toString());
        assertThat(TinkarServiceImpl.identifierFor(undescribedNid)).isEqualTo(UNDESCRIBED.toString());
        assertNoNid("the semantics of a concept with no description", response.toString(), undescribedNid);
    }

    @Test
    void aComponentFieldThatHoldsItIsItsUuid() {
        TinkarConceptSemanticInfo identifier =
                semanticOfPattern(service.inspectConceptProto(HOLDER.toString()), KernelTerm.IDENTIFIER_PATTERN);

        assertThat(identifier.getNamedFieldsList().stream().map(TinkarSemanticField::getValue))
                .containsExactlyInAnyOrder(UNDESCRIBED.toString(), "H-0001");
        assertNoNid("the identifier semantic", identifier.toString(), undescribedNid);
    }

    @Test
    void anIdSetIsNamesWithTypedIdentifiersAndNeverANid() {
        TinkarConceptSemanticInfo idSet =
                semanticOfPattern(service.inspectConceptProto(HOLDER.toString()), KernelTerm.STATED_NAVIGATION_PATTERN);

        // In the order the set holds its elements.
        List<String> elements = new ArrayList<>();
        for (int nid : idSetUnderTest().toArray()) {
            if (nid == undescribedNid) {
                elements.add(UNDESCRIBED + " [UUID " + UNDESCRIBED + "]");
            } else if (nid == UNASSIGNED_NID) {
                elements.add("unidentified component [unidentified component]");
            } else {
                elements.add(name(KernelTerm.ENGLISH_LANGUAGE) + " [UUID " + uuidOf(KernelTerm.ENGLISH_LANGUAGE) + "]");
            }
        }
        assertThat(idSet.getNamedFields(0).getValue()).isEqualTo("[" + String.join(", ", elements) + "]");
        assertThat(idSet.getNamedFields(1).getValue()).isEqualTo("[]");
        assertNoNid("the id set semantic", idSet.toString(), undescribedNid, UNASSIGNED_NID);
    }

    @Test
    void aDefinitionThatRefersToItNamesItByItsUuid() {
        TinkarConceptSemanticInfo definition =
                semanticOfPattern(service.inspectConceptProto(HOLDER.toString()), KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN);

        String expected = "\n"
                + "   [0]➞[1] " + name(KernelTerm.DEFINITION_ROOT) + "\n"
                + "     [1]➞[2] " + name(KernelTerm.NECESSARY_SET) + "\n"
                + "       [2]➞[3,4] " + name(KernelTerm.AND) + "\n"
                + "         [3] " + name(KernelTerm.CONCEPT_REFERENCE) + ": " + UNDESCRIBED + "\n"
                + "         [4] " + name(KernelTerm.ROLE) + "\n"
                + "            •" + name(KernelTerm.ROLE_TYPE) + ": " + UNDESCRIBED + "\n";
        assertThat(onlyFieldOf(definition)).isEqualTo(expected);
        assertNoNid("the definition semantic", definition.toString(), undescribedNid);
    }

    @Test
    void theSameTextIsReturnedForOneSemanticAskedForByItsOwnId() {
        TinkarSemanticInfoResponse response = service.getSemanticInfo(ID_SET.toString());

        assertThat(response.getSuccess()).isTrue();
        assertThat(response.getSemantic())
                .isEqualTo(semanticOfPattern(service.inspectConceptProto(HOLDER.toString()), KernelTerm.STATED_NAVIGATION_PATTERN));
        assertNoNid("the semantic", response.toString(), undescribedNid, UNASSIGNED_NID);
    }

    @Test
    void theRestFormOfTheSemanticsHoldsNoNid() {
        ConceptSemanticsResponse response = service.inspectConcept(HOLDER.toString());

        assertThat(response.success()).isTrue();
        assertThat(response.conceptDescription()).isEqualTo(HOLDER_NAME);
        assertThat(response.semantics()).hasSizeGreaterThanOrEqualTo(4);
        assertNoNid("the semantics as the REST controller returns them", response.toString(),
                undescribedNid, holderNid, UNASSIGNED_NID);
    }

    // ── A semantic, which has no description of its own ──────────────────────

    @Test
    void aSemanticIsDescribedByWhatItIsAndNamesAnUndescribedConceptByItsUuid() {
        ChangeHistoryResponse response = service.getChangeHistory(BARE.toString());

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        assertThat(response.entityDescription())
                .isEqualTo(name(KernelTerm.INFERRED_NAVIGATION_PATTERN) + " semantic on " + UNDESCRIBED);
        assertNoNid("the change history of a semantic", response.toString(), undescribedNid);
    }

    @Test
    void theChangeHistoryOfAConceptHoldsNoNid() {
        ConceptChangeHistoryResponse response = service.getConceptChangeHistory(HOLDER.toString());

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        assertThat(response.conceptDescription()).isEqualTo(HOLDER_NAME);
        assertThat(response.semanticChanges()).hasSizeGreaterThanOrEqualTo(4);
        assertThat(response.toString())
                .as("the identifier's source, added in its first version, is written by UUID")
                .contains(UNDESCRIBED.toString());
        assertNoNid("the change history of a concept", response.toString(),
                undescribedNid, holderNid, UNASSIGNED_NID);
    }

    // ── Search results ───────────────────────────────────────────────────────

    @Test
    void aGroupedSearchResultNamesAnUndescribedConceptByItsUuidAndLeavesThePreferredNameEmpty() {
        for (SearchSortOption sort : List.of(SearchSortOption.TOP_COMPONENT, SearchSortOption.TOP_COMPONENT_ALPHA)) {
            ConceptSearchResponse response = service.conceptSearchWithSort(SEARCH_WORD, 10, sort);

            assertThat(response.success()).as(response.errorMessage()).isTrue();
            assertThat(response.groupedResults()).as("one concept holds the word").hasSize(1);
            ConceptSearchResponse.GroupedSearchResult group = response.groupedResults().getFirst();
            assertThat(group.publicId()).containsExactly(UNDESCRIBED.toString());
            assertThat(group.fullyQualifiedName()).isEqualTo(UNDESCRIBED.toString());
            assertThat(group.preferredName())
                    .as("no preferred name, so the caller's label falls back to the fully qualified name")
                    .isNull();
            assertThat(group.highlightedName()).isNull();
            assertThat(group.matchingSemantics()).hasSize(1);
            assertThat(group.matchingSemantics().getFirst().plainText()).contains(SEARCH_WORD);
            assertThat(group.matchingSemantics().getFirst().publicId()).containsExactly(COMMENT.toString());
            // The whole of the result, as the REST controller returns it and as the gRPC
            // controller sends it: neither has a field for a nid (IKE-Network/ike-issues#1182).
            assertNoNid("a grouped result", group.toString(), undescribedNid, commentNid);
            assertNoNid("a grouped result as a gRPC message",
                    ProtoConversionUtils.toConceptSearchWithSortProto(response).toString(), undescribedNid, commentNid);
        }
    }

    @Test
    void theJsonOfASearchResponseHasNoKeyForANid() throws IOException {
        ObjectMapper json = new ObjectMapper();
        for (SearchSortOption sort : SearchSortOption.values()) {
            String text = json.writeValueAsString(service.conceptSearchWithSort(SEARCH_WORD, 10, sort));

            List<String> keys = new ArrayList<>();
            collectKeys(json.readTree(text), keys);
            assertThat(keys).as("the keys of a " + sort + " response").contains("publicId", "fullyQualifiedName");
            assertThat(keys).as("keys named for a nid in " + text)
                    .noneMatch(key -> key.toLowerCase().contains("nid"));
            assertNoNid("the JSON of a " + sort + " response", text, undescribedNid, commentNid);
        }
    }

    @Test
    void aFlatSearchResultNamesAnUndescribedConceptByItsUuid() {
        for (SearchSortOption sort : List.of(SearchSortOption.SEMANTIC, SearchSortOption.SEMANTIC_ALPHA)) {
            ConceptSearchResponse response = service.conceptSearchWithSort(SEARCH_WORD, 10, sort);

            assertThat(response.success()).as(response.errorMessage()).isTrue();
            assertThat(response.results()).hasSize(1);
            ConceptSearchResponse.SemanticSearchResult result = response.results().getFirst();
            assertThat(result.publicId()).containsExactly(UNDESCRIBED.toString());
            assertThat(result.fullyQualifiedName()).isEqualTo(UNDESCRIBED.toString());
            assertThat(result.regularName()).isNull();
            assertNoNid("a flat result", result.toString(), undescribedNid, commentNid);
        }
    }

    @Test
    void aSearchResultForADescribedConceptReadsAsItDid() {
        ConceptSearchResponse response = service.conceptSearchWithSort("English language", 20, SearchSortOption.TOP_COMPONENT);

        assertThat(response.success()).as(response.errorMessage()).isTrue();
        ConceptSearchResponse.GroupedSearchResult english = response.groupedResults().stream()
                .filter(group -> group.publicId().contains(uuidOf(KernelTerm.ENGLISH_LANGUAGE)))
                .findFirst().orElseThrow();
        LanguageCalculator names = Calculators.View.Default().languageCalculator();
        int nid = KernelTerm.ENGLISH_LANGUAGE.nid();
        assertThat(english.fullyQualifiedName()).isEqualTo(names.getFullyQualifiedNameText(nid).orElseThrow());
        assertThat(english.preferredName()).isEqualTo(names.getDescriptionText(nid).orElseThrow());
    }

    // ── Field values the store cannot hold in a semantic ─────────────────────

    @Test
    void aVertexIsWrittenOnOneLineOfNames() {
        EntityVertex role = EntityVertex.make(KernelTerm.ROLE);
        setProperty(role, KernelTerm.ROLE_TYPE, EntityProxy.Concept.make(PublicIds.of(UNDESCRIBED)));

        String text = service.formatFieldValue(role);

        assertThat(text).isEqualTo(name(KernelTerm.ROLE) + " {" + name(KernelTerm.ROLE_TYPE) + "=" + UNDESCRIBED + "}");
        assertThat(role.toString()).as("the vertex's own text ends its component in a nid").contains("<" + undescribedNid + ">");
        assertNoNid("the vertex", text, undescribedNid);
        // A vertex has a UUID of its own, which makes it a public id, but it is not a component.
        assertThat(role.asUuidList().anySatisfy(uuid -> PrimitiveData.get().hasUuid(uuid)))
                .as("writing a vertex must not ask the store for a nid for the vertex's own UUID")
                .isFalse();
    }

    @Test
    void aComponentIsItsNameOrItsUuid() {
        assertThat(service.formatFieldValue(KernelTerm.ENGLISH_LANGUAGE)).isEqualTo(name(KernelTerm.ENGLISH_LANGUAGE));
        assertThat(service.formatFieldValue(EntityProxy.Concept.make(PublicIds.of(UNDESCRIBED)))).isEqualTo(UNDESCRIBED.toString());
        assertThat(service.formatFieldValue(PublicIds.of(UNDESCRIBED))).isEqualTo(UNDESCRIBED.toString());
    }

    @Test
    void aPublicIdIsWrittenAsItsUuidsNeverAsAProxyWritesItself() {
        UUID first = fixtureUuid("first uuid of two");
        UUID second = fixtureUuid("second uuid of two");
        EntityProxy.Concept proxy = EntityProxy.Concept.make(PublicIds.of(UNDESCRIBED));
        int nid = proxy.nid();

        assertThat(proxy.toString()).as("a proxy's own text ends in its nid").contains("<" + nid + ">");
        assertThat(TinkarServiceImpl.uuidsOf(proxy)).isEqualTo(UNDESCRIBED.toString());
        assertThat(TinkarServiceImpl.uuidsOf(PublicIds.of(first, second))).isEqualTo(first + ", " + second);
        assertThat(TinkarServiceImpl.uuidsOf(PublicIds.of(new UUID[0]))).isEqualTo(TinkarServiceImpl.UNIDENTIFIED);
    }

    @Test
    void aComponentTheStoreHasNoPublicIdForIsStatedAsUnidentified() {
        assertThat(TinkarServiceImpl.identifierFor(UNASSIGNED_NID)).isEqualTo("unidentified component");
        assertThat(service.formatFieldValue(IntIds.list.of(UNASSIGNED_NID)))
                .isEqualTo("[unidentified component [unidentified component]]");
    }

    @Test
    void aValueThatIsNotAComponentIsItsOwnText() {
        assertThat(service.formatFieldValue("some text")).isEqualTo("some text");
        assertThat(service.formatFieldValue(42)).isEqualTo("42");
        assertThat(service.formatFieldValue(true)).isEqualTo("true");
        assertThat(service.formatFieldValue(null)).isNull();
    }

    // ── What the tests write into the store ──────────────────────────────────

    /**
     * Writes the components under test, in one committed transaction on the development path:
     * a concept with no description; a described concept that holds an identifier, an id set,
     * and a definition, each referring to the undescribed concept; and under the undescribed
     * concept a semantic with nothing attached and a semantic with a comment attached.
     *
     * <p>The undescribed concept has no text of its own anywhere: its two semantics hold id
     * sets, and the comment is attached to one of them, not to the concept. A search for the
     * comment's word therefore finds the concept, and nothing gives the concept a name.
     */
    private void writeTheComponentsUnderTest() {
        for (UUID uuid : List.of(UNDESCRIBED, HOLDER, IDENTIFIER, ID_SET, DEFINITION, BARE, COMMENTED, COMMENT)) {
            assertThat(EPHEMERAL_NID.matcher(uuid.toString()).find())
                    .as("a UUID written by the test must not look like a nid: " + uuid)
                    .isFalse();
        }
        Transaction transaction = Transaction.make("Components for the response text tests");
        StampEntity<?> stamp = transaction.getStamp(State.ACTIVE, System.currentTimeMillis(),
                KernelTerm.USER.nid(), KernelTerm.SOLOR_OVERLAY_MODULE.nid(), KernelTerm.DEVELOPMENT_PATH.nid());
        StampEntityVersion version = stamp.versions().get(0);

        undescribedNid = put(transaction, ConceptRecord.build(UNDESCRIBED, version));
        holderNid = put(transaction, ConceptRecord.build(HOLDER, version));
        EntityProxy.Concept undescribed = EntityProxy.Concept.make(PublicIds.of(UNDESCRIBED));

        put(transaction, SemanticRecord.build(fixtureUuid("holder name"), KernelTerm.DESCRIPTION_PATTERN.nid(),
                holderNid, version, Lists.immutable.of(
                        KernelTerm.ENGLISH_LANGUAGE.publicId(),
                        HOLDER_NAME,
                        KernelTerm.DESCRIPTION_NOT_CASE_SENSITIVE.publicId(),
                        KernelTerm.FULLY_QUALIFIED_NAME_DESCRIPTION_TYPE.publicId())));

        put(transaction, SemanticRecord.build(IDENTIFIER, KernelTerm.IDENTIFIER_PATTERN.nid(),
                holderNid, version, Lists.immutable.of(undescribed, "H-0001")));
        put(transaction, SemanticRecord.build(ID_SET, KernelTerm.STATED_NAVIGATION_PATTERN.nid(),
                holderNid, version, Lists.immutable.of(idSetUnderTest(), IntIds.set.empty())));
        put(transaction, SemanticRecord.build(DEFINITION, KernelTerm.EL_PLUS_PLUS_STATED_AXIOMS_PATTERN.nid(),
                holderNid, version, fields(aDefinitionThatRefersTo(undescribed))));

        put(transaction, SemanticRecord.build(BARE, KernelTerm.INFERRED_NAVIGATION_PATTERN.nid(),
                undescribedNid, version, Lists.immutable.of(IntIds.set.empty(), IntIds.set.empty())));
        int commentedNid = put(transaction, SemanticRecord.build(COMMENTED, KernelTerm.STATED_NAVIGATION_PATTERN.nid(),
                undescribedNid, version, Lists.immutable.of(IntIds.set.of(holderNid), IntIds.set.empty())));
        commentNid = put(transaction, SemanticRecord.build(COMMENT, KernelTerm.COMMENT_PATTERN.nid(),
                commentedNid, version, fields(SEARCH_WORD + " is a word no description holds")));

        transaction.commit();
    }

    /** A described concept, the undescribed concept, and a nid the store has no public id for. */
    private IntIdSet idSetUnderTest() {
        return IntIds.set.of(KernelTerm.ENGLISH_LANGUAGE.nid(), undescribedNid, UNASSIGNED_NID);
    }

    /**
     * A definition whose necessary set holds a reference to the given concept and a role whose
     * type is the given concept.
     */
    private static DiTreeEntity aDefinitionThatRefersTo(EntityProxy.Concept concept) {
        EntityVertex root = EntityVertex.make(KernelTerm.DEFINITION_ROOT);
        EntityVertex necessarySet = EntityVertex.make(KernelTerm.NECESSARY_SET);
        EntityVertex and = EntityVertex.make(KernelTerm.AND);
        EntityVertex reference = EntityVertex.make(KernelTerm.CONCEPT_REFERENCE);
        setProperty(reference, KernelTerm.CONCEPT_REFERENCE, concept);
        EntityVertex role = EntityVertex.make(KernelTerm.ROLE);
        setProperty(role, KernelTerm.ROLE_TYPE, concept);

        DiTreeEntity.Builder builder = DiTreeEntity.builder();
        builder.setRoot(root);
        builder.addEdge(necessarySet, root);
        builder.addEdge(and, necessarySet);
        builder.addEdge(reference, and);
        builder.addEdge(role, and);
        return builder.build();
    }

    /** Gives a vertex one property. */
    private static void setProperty(EntityVertex vertex, EntityFacade key, Object value) {
        MutableIntObjectMap<Object> properties = IntObjectMaps.mutable.empty();
        properties.put(key.nid(), value);
        vertex.setProperties(properties);
    }

    /** A semantic's field values, for a semantic with one field. */
    private static ImmutableList<Object> fields(Object value) {
        return Lists.immutable.of(value);
    }

    /** Writes an entity into the store and the transaction. */
    private static int put(Transaction transaction, Entity<?> entity) {
        EntityService.get().putEntity(entity);
        transaction.addComponent(entity);
        return entity.nid();
    }

    /** A UUID for a component the tests write: the same in every run. */
    private static UUID fixtureUuid(String what) {
        return UUID.nameUUIDFromBytes(("tinkar-service response text test: " + what).getBytes(StandardCharsets.UTF_8));
    }

    // ── What the tests read ──────────────────────────────────────────────────

    /** The name the service writes for a described component: its regular name, else its fully qualified name. */
    private static String name(EntityFacade component) {
        return name(component.nid());
    }

    private static String name(int nid) {
        LanguageCalculator names = Calculators.View.Default().languageCalculator();
        return names.getRegularDescriptionText(nid)
                .or(() -> names.getFullyQualifiedNameText(nid))
                .orElseThrow();
    }

    /** The UUID response text names a component by: the least of its UUIDs, as the service writes it. */
    private static String uuidOf(EntityFacade component) {
        return component.publicId().leastUuid().toString();
    }

    /** Adds every key of a JSON value, at any depth. */
    private static void collectKeys(JsonNode node, List<String> keys) {
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(keys::add);
        }
        for (JsonNode child : node) {
            collectKeys(child, keys);
        }
    }

    /** The one semantic of a pattern among a concept's semantics. */
    private static TinkarConceptSemanticInfo semanticOfPattern(TinkarConceptSemanticsResponse response, EntityFacade pattern) {
        assertThat(response.getSuccess()).as(response.getErrorMessage()).isTrue();
        List<TinkarConceptSemanticInfo> ofPattern = response.getSemanticsList().stream()
                .filter(semantic -> semantic.getPatternName().equals(name(pattern)))
                .toList();
        assertThat(ofPattern).as("semantics of " + name(pattern)).hasSize(1);
        return ofPattern.getFirst();
    }

    /** The value of the one field of a semantic, which the positional and the named form both hold. */
    private static String onlyFieldOf(TinkarConceptSemanticInfo semantic) {
        assertThat(semantic.getNamedFieldsCount()).isEqualTo(1);
        assertThat(semantic.getFields(0).getStringValue()).isEqualTo(semantic.getNamedFields(0).getValue());
        return semantic.getNamedFields(0).getValue();
    }

    /**
     * Fails when the text holds a nid: a number of the shape the store assigns, a nid in one of
     * the forms it has been written in, or one of the given nids in decimal.
     */
    private static void assertNoNid(String what, String text, int... nids) {
        for (Pattern form : new Pattern[]{EPHEMERAL_NID, ANGLE_BRACKET_NID, LABELLED_NID}) {
            Matcher matcher = form.matcher(text);
            if (matcher.find()) {
                fail(what + " holds a nid: \"" + matcher.group() + "\" in:\n" + text);
            }
        }
        for (int nid : nids) {
            if (text.contains(Integer.toString(nid))) {
                fail(what + " holds the nid " + nid + " in:\n" + text);
            }
        }
    }
}
