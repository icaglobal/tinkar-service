package dev.ikm.tinkar.service;

import com.google.protobuf.Descriptors.Descriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FileDescriptor;
import dev.ikm.tinkar.service.proto.IkeAdminProto;
import dev.ikm.tinkar.service.proto.IkeGraphRAGProto;
import dev.ikm.tinkar.service.proto.IkeKnowledgeGraphProto;
import dev.ikm.tinkar.service.proto.IkeTypesProto;
import dev.ikm.tinkar.service.proto.TinkarGroupedSearchResult;
import dev.ikm.tinkar.service.proto.TinkarMatchingSemantic;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * No response of the service has a field for a nid ({@code IKE-Network/ike-issues#1182}).
 *
 * <p>A nid is local to the server's store, so a caller cannot use one. A grouped search result
 * used to hold two — {@code concept_nid} on the result and {@code semantic_nid} on each matching
 * semantic, {@code conceptNid} and {@code semanticNid} in the REST JSON — each beside the
 * public id of the same component. They were deleted, and in the gRPC messages their numbers
 * and names were reserved so that neither is used again for something else.
 *
 * <p>These tests hold the schemas and the REST records to that: the two reservations are in
 * place, and no message and no record has a field named for a nid.
 */
class NoNidFieldInResponsesTest {

    /** The REST records, relative to the module directory the tests run in. */
    private static final Path REST_RECORDS = Path.of("src", "main", "java", "dev", "ikm", "tinkar", "service", "dto");

    private static final String REST_RECORDS_PACKAGE = "dev.ikm.tinkar.service.dto";

    /** A message field named for a nid: {@code nid}, {@code nids}, {@code concept_nid}, {@code nid_list}. */
    private static final Pattern MESSAGE_FIELD_FOR_A_NID = Pattern.compile("(^|_)nids?(_|$)");

    /** A record component named for a nid: {@code nid}, {@code nids}, {@code conceptNid}, {@code nidList}. */
    private static final Pattern RECORD_COMPONENT_FOR_A_NID = Pattern.compile("^nids?($|[A-Z])|Nids?($|[A-Z])");

    @Test
    void theNumbersAndNamesOfTheDeletedFieldsAreReserved() {
        Descriptor matchingSemantic = TinkarMatchingSemantic.getDescriptor();
        assertThat(matchingSemantic.findFieldByName("semantic_nid")).isNull();
        assertThat(matchingSemantic.findFieldByNumber(5)).isNull();
        assertThat(matchingSemantic.isReservedNumber(5)).as("field 5 of TinkarMatchingSemantic is reserved").isTrue();
        assertThat(matchingSemantic.isReservedName("semantic_nid")).isTrue();

        Descriptor groupedResult = TinkarGroupedSearchResult.getDescriptor();
        assertThat(groupedResult.findFieldByName("concept_nid")).isNull();
        assertThat(groupedResult.findFieldByNumber(6)).isNull();
        assertThat(groupedResult.isReservedNumber(6)).as("field 6 of TinkarGroupedSearchResult is reserved").isTrue();
        assertThat(groupedResult.isReservedName("concept_nid")).isTrue();
    }

    @Test
    void theFieldsBesideTheDeletedOnesKeepTheirNumbers() {
        // Deleting a field must not renumber its neighbors: a number is what goes on the wire.
        Descriptor matchingSemantic = TinkarMatchingSemantic.getDescriptor();
        assertThat(matchingSemantic.findFieldByName("field_index").getNumber()).isEqualTo(4);
        assertThat(matchingSemantic.findFieldByName("public_id").getNumber()).isEqualTo(6);

        Descriptor groupedResult = TinkarGroupedSearchResult.getDescriptor();
        assertThat(groupedResult.findFieldByName("matching_semantics").getNumber()).isEqualTo(5);
        assertThat(groupedResult.findFieldByName("preferred_name").getNumber()).isEqualTo(7);
        assertThat(groupedResult.findFieldByName("highlighted_name").getNumber()).isEqualTo(8);
    }

    @Test
    void noMessageOfTheServiceSchemasHasAFieldForANid() {
        List<Descriptor> messages = new ArrayList<>();
        for (FileDescriptor schema : List.of(IkeTypesProto.getDescriptor(), IkeGraphRAGProto.getDescriptor(),
                IkeKnowledgeGraphProto.getDescriptor(), IkeAdminProto.getDescriptor())) {
            for (Descriptor message : schema.getMessageTypes()) {
                collect(message, messages);
            }
        }
        assertThat(messages).as("messages read; too few means the schemas were not read").hasSizeGreaterThan(40);

        List<String> fieldsForANid = new ArrayList<>();
        for (Descriptor message : messages) {
            for (FieldDescriptor field : message.getFields()) {
                if (MESSAGE_FIELD_FOR_A_NID.matcher(field.getName()).find()) {
                    fieldsForANid.add(message.getFullName() + "." + field.getName());
                }
            }
        }
        assertThat(fieldsForANid).as("message fields named for a nid; send the public id instead").isEmpty();
    }

    @Test
    void noRestRecordHasAComponentForANid() throws IOException, ClassNotFoundException {
        assertThat(REST_RECORDS)
                .as("The test reads " + REST_RECORDS.toAbsolutePath() + "; run the tests from the module directory.")
                .isDirectory();

        List<Class<?>> records = new ArrayList<>();
        try (Stream<Path> files = Files.list(REST_RECORDS)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).sorted().toList()) {
                String name = file.getFileName().toString();
                collect(Class.forName(REST_RECORDS_PACKAGE + "." + name.substring(0, name.length() - ".java".length())),
                        records);
            }
        }
        assertThat(records).as("records read; too few means the package was not read").hasSizeGreaterThan(20);

        List<String> componentsForANid = new ArrayList<>();
        for (Class<?> record : records) {
            for (RecordComponent component : record.getRecordComponents()) {
                if (RECORD_COMPONENT_FOR_A_NID.matcher(component.getName()).find()) {
                    componentsForANid.add(record.getName() + "." + component.getName());
                }
            }
        }
        assertThat(componentsForANid).as("record components named for a nid; send the public id instead").isEmpty();
    }

    @Test
    void theNamePatternsRecognizeANidAndNothingElse() {
        for (String name : List.of("nid", "nids", "concept_nid", "semantic_nids", "nid_list", "top_nid_count")) {
            assertThat(MESSAGE_FIELD_FOR_A_NID.matcher(name).find()).as(name).isTrue();
        }
        for (String name : List.of("public_id", "field_index", "unidentified", "created_at", "snide_remark")) {
            assertThat(MESSAGE_FIELD_FOR_A_NID.matcher(name).find()).as(name).isFalse();
        }
        for (String name : List.of("nid", "nids", "conceptNid", "semanticNids", "nidList", "topNidCount")) {
            assertThat(RECORD_COMPONENT_FOR_A_NID.matcher(name).find()).as(name).isTrue();
        }
        for (String name : List.of("publicId", "fieldIndex", "unidentified", "createdAt", "snideRemark", "conceptId")) {
            assertThat(RECORD_COMPONENT_FOR_A_NID.matcher(name).find()).as(name).isFalse();
        }
    }

    /** Adds a message and every message nested in it. */
    private static void collect(Descriptor message, List<Descriptor> messages) {
        messages.add(message);
        for (Descriptor nested : message.getNestedTypes()) {
            collect(nested, messages);
        }
    }

    /** Adds a class, when it is a record, and every record nested in it. */
    private static void collect(Class<?> type, List<Class<?>> records) {
        if (type.isRecord()) {
            records.add(type);
        }
        for (Class<?> nested : type.getDeclaredClasses()) {
            collect(nested, records);
        }
    }
}
