package dev.ikm.tinkar.service.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * What to create a concept from: its descriptions and its logical definition.
 *
 * <p>Shaped after Komet's New Concept editor, which is the reference for what a caller can
 * author. A JSON body rather than query parameters because a concept carries a variable number
 * of descriptions and axioms, each with several fields of its own.
 *
 * @param descriptions at least one, exactly one of which must be the fully qualified name
 * @param axioms       the logical definition; empty means a necessary set referencing
 *                     Anonymous concept, the placeholder Komet writes for an unfinished definition
 */
@Schema(description = "A concept to create, with its descriptions and axioms")
public record CreateConceptRequest(
        @Schema(description = "Descriptions to attach; exactly one must be FULLY_QUALIFIED_NAME")
        List<Description> descriptions,

        @Schema(description = "Logical definition; omit for a necessary set referencing Anonymous concept")
        List<Axiom> axioms) {

    /** Which kind of description this is — the DESCRIPTION_PATTERN's description-type field. */
    public enum DescriptionType {
        FULLY_QUALIFIED_NAME,
        REGULAR_NAME,
        DEFINITION
    }

    /**
     * How the description's capitalisation should be interpreted.
     *
     * <p>A value for the pattern's case-significance field. {@code NOT_CASE_SENSITIVE} is the
     * usual choice and the default applied when a caller omits it.
     */
    public enum CaseSignificance {
        CASE_SENSITIVE,
        NOT_CASE_SENSITIVE,
        INITIAL_CHARACTER_CASE_SENSITIVE
    }

    /** Language the description is written in. Defaults to {@code ENGLISH}. */
    public enum Language {
        ENGLISH,
        SPANISH,
        FRENCH,
        GERMAN,
        DUTCH,
        ITALIAN,
        DANISH,
        CZECH,
        IRISH,
        CHINESE
    }

    /**
     * Whether the set's conditions merely hold of the concept, or define it.
     *
     * <p>{@code NECESSARY} states every instance satisfies the conditions but they do not
     * identify the concept; {@code SUFFICIENT} states anything satisfying them <em>is</em> the
     * concept, which is what lets the reasoner classify other concepts underneath it.
     */
    public enum AxiomSetType {
        NECESSARY,
        SUFFICIENT
    }

    /**
     * One description on the new concept.
     *
     * @param text             the description itself; required
     * @param type             which kind of description; required
     * @param caseSignificance defaults to {@code NOT_CASE_SENSITIVE}
     * @param language         defaults to {@code ENGLISH}
     */
    @Schema(description = "A description to attach to the concept")
    public record Description(
            @Schema(description = "The description text", example = "New Medical Condition") String text,
            @Schema(description = "Which kind of description this is") DescriptionType type,
            @Schema(description = "How capitalisation should be read; defaults to NOT_CASE_SENSITIVE") CaseSignificance caseSignificance,
            @Schema(description = "Language of the text; defaults to ENGLISH") Language language) {
    }

    /**
     * One set in the concept's stated axiom.
     *
     * @param setType          necessary or sufficient; required
     * @param parentConceptIds public IDs (UUIDs) the set references; empty means Anonymous concept
     */
    @Schema(description = "A necessary or sufficient set in the stated axiom")
    public record Axiom(
            @Schema(description = "Whether the conditions merely hold, or define the concept") AxiomSetType setType,
            @Schema(description = "Concepts the set references") List<String> parentConceptIds) {
    }
}
