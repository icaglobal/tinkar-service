package ai.ica.tinkar.controller.knowledgegraph;

import ai.ica.tinkar.dto.ChangeHistoryResponse;
import ai.ica.tinkar.dto.ConceptChangeHistoryResponse;
import ai.ica.tinkar.dto.ConceptSemanticsResponse;
import ai.ica.tinkar.dto.DescendantOperationResponse;
import ai.ica.tinkar.service.TinkarService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tier 2: Concept-Aware (Knowledge Graph) — REST controller.
 *
 * Exposes the concept-oriented structure with semantic patterns, STAMP info,
 * version history, and write operations. Target audience: analytics engineers,
 * knowledge graph practitioners.
 */
@RestController
@RequestMapping("/api/ike/knowledgegraph")
@Tag(name = "IKE Knowledge Graph (Tier 2)", description = "Concept-aware API exposing STAMP coordinates, semantic patterns, and version history.")
public class KnowledgeGraphRestController {

    private final TinkarService tinkarService;

    public KnowledgeGraphRestController(TinkarService tinkarService) {
        this.tinkarService = tinkarService;
    }

    @Operation(summary = "Get all semantics for a concept", description = "Retrieves all semantics (comments, descriptions, axioms, etc.) attached to a concept.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Semantics retrieved successfully", content = @Content(schema = @Schema(implementation = ConceptSemanticsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID parameter")
    })
    @GetMapping("/semantics")
    public ResponseEntity<ConceptSemanticsResponse> getConceptSemantics(
            @Parameter(description = "Concept ID (UUID)", required = true, example = "9fc3832b-a5f8-5504-ba16-7551976841dc") @RequestParam("conceptId") String conceptId) {
        return ResponseEntity.ok(tinkarService.getConceptSemantics(conceptId));
    }

    @Operation(summary = "Get comments for a concept", description = "Retrieves all comment semantics attached to a concept.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Comments retrieved successfully", content = @Content(schema = @Schema(implementation = ConceptSemanticsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID parameter")
    })
    @GetMapping("/comments")
    public ResponseEntity<ConceptSemanticsResponse> getConceptComments(
            @Parameter(description = "Concept ID (UUID)", required = true, example = "9fc3832b-a5f8-5504-ba16-7551976841dc") @RequestParam("conceptId") String conceptId) {
        return ResponseEntity.ok(tinkarService.getConceptComments(conceptId));
    }

    @Operation(summary = "Get change history for an entity", description = "Retrieves the complete change history for an entity, showing all STAMP versions and field modifications.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Change history retrieved successfully", content = @Content(schema = @Schema(implementation = ChangeHistoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid entity ID parameter")
    })
    @GetMapping("/change-history")
    public ResponseEntity<ChangeHistoryResponse> getChangeHistory(
            @Parameter(description = "Entity ID (UUID)", required = true, example = "f5c39ec3-7256-3a03-b651-d17b623a30ec") @RequestParam("entityId") String entityId) {
        return ResponseEntity.ok(tinkarService.getChangeHistory(entityId));
    }

    @Operation(summary = "Get comprehensive change history for a concept", description = "Retrieves the full change history for a concept INCLUDING all attached semantics.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Change history retrieved successfully", content = @Content(schema = @Schema(implementation = ConceptChangeHistoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID parameter")
    })
    @GetMapping("/concept-change-history")
    public ResponseEntity<ConceptChangeHistoryResponse> getConceptChangeHistory(
            @Parameter(description = "Concept ID (UUID)", required = true, example = "9fc3832b-a5f8-5504-ba16-7551976841dc") @RequestParam("conceptId") String conceptId) {
        return ResponseEntity.ok(tinkarService.getConceptChangeHistory(conceptId));
    }

    @Operation(summary = "Create a sample change (comment)", description = "Creates a new comment semantic attached to the specified concept.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Change created successfully", content = @Content(schema = @Schema(implementation = ChangeHistoryResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID or comment parameter")
    })
    @PostMapping("/changes")
    public ResponseEntity<ChangeHistoryResponse> createSampleChange(
            @Parameter(description = "Concept ID (UUID)", required = true, example = "f5c39ec3-7256-3a03-b651-d17b623a30ec") @RequestParam("conceptId") String conceptId,
            @Parameter(description = "Comment text to add", required = true, example = "This is a sample comment") @RequestParam("comment") String comment) {
        return ResponseEntity.ok(tinkarService.createSampleChange(conceptId, comment));
    }

    @Operation(summary = "Save pending changes", description = "Saves all pending changes to persistent storage (disk).")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Save operation completed"),
            @ApiResponse(responseCode = "500", description = "Failed to save changes")
    })
    @PostMapping("/save")
    public ResponseEntity<String> saveChanges() {
        return ResponseEntity.ok(tinkarService.saveChanges());
    }

    @Operation(summary = "Discard pending changes", description = "Discards all pending changes that have not been saved to disk.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Discard operation completed"),
            @ApiResponse(responseCode = "500", description = "Failed to discard changes")
    })
    @PostMapping("/discard")
    public ResponseEntity<String> discardChanges() {
        return ResponseEntity.ok(tinkarService.discardChanges());
    }

    @Operation(summary = "Add a descendant to a concept", description = "Creates a new IS-A relationship making an existing concept a descendant (child) of the specified parent concept.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Descendant added successfully", content = @Content(schema = @Schema(implementation = DescendantOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID parameter")
    })
    @PostMapping("/descendants")
    public ResponseEntity<DescendantOperationResponse> addDescendant(
            @Parameter(description = "Parent concept ID (UUID)", required = true, example = "f6978e15-e169-58c2-a93d-eac1511974da") @RequestParam("parentConceptId") String parentConceptId,
            @Parameter(description = "Concept ID to add as descendant (UUID)", required = true, example = "9fc3832b-a5f8-5504-ba16-7551976841dc") @RequestParam("descendantConceptId") String descendantConceptId) {
        return ResponseEntity.ok(tinkarService.addDescendant(parentConceptId, descendantConceptId));
    }

    @Operation(summary = "Create a new concept and add as descendant", description = "Creates a new concept with the specified name and establishes an IS-A relationship with the parent concept.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "New concept created and added as descendant successfully", content = @Content(schema = @Schema(implementation = DescendantOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID or name parameter")
    })
    @PostMapping("/descendants/create")
    public ResponseEntity<DescendantOperationResponse> createAndAddDescendant(
            @Parameter(description = "Parent concept ID (UUID)", required = true, example = "f6978e15-e169-58c2-a93d-eac1511974da") @RequestParam("parentConceptId") String parentConceptId,
            @Parameter(description = "Name for the new concept", required = true, example = "New Medical Condition") @RequestParam("conceptName") String conceptName) {
        return ResponseEntity.ok(tinkarService.createAndAddDescendant(parentConceptId, conceptName));
    }

    @Operation(summary = "Remove a descendant from a concept", description = "Removes the IS-A relationship between a parent concept and a descendant concept.")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Descendant removed successfully", content = @Content(schema = @Schema(implementation = DescendantOperationResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid concept ID parameter")
    })
    @DeleteMapping("/descendants")
    public ResponseEntity<DescendantOperationResponse> removeDescendant(
            @Parameter(description = "Parent concept ID (UUID)", required = true, example = "f6978e15-e169-58c2-a93d-eac1511974da") @RequestParam("parentConceptId") String parentConceptId,
            @Parameter(description = "Descendant concept ID to remove (UUID)", required = true, example = "9fc3832b-a5f8-5504-ba16-7551976841dc") @RequestParam("descendantConceptId") String descendantConceptId) {
        return ResponseEntity.ok(tinkarService.removeDescendant(parentConceptId, descendantConceptId));
    }
}
