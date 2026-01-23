package ai.ica.tinkar.service;

import ai.ica.tinkar.dto.ChangeHistoryResponse;
import ai.ica.tinkar.dto.ConceptChangeHistoryResponse;
import ai.ica.tinkar.dto.ConceptSemanticsResponse;
import ai.ica.tinkar.proto.TinkarSearchQueryResponse;

public interface TinkarService {
    TinkarSearchQueryResponse search(String query);

    TinkarSearchQueryResponse conceptSearch(String query, Integer maxResults);

    TinkarSearchQueryResponse getEntity(String conceptId);

    TinkarSearchQueryResponse getChildConcepts(String conceptId);

    TinkarSearchQueryResponse getDescendantConcepts(String conceptId);

    TinkarSearchQueryResponse getLIDRRecordConceptsFromTestKit(String conceptId);

    TinkarSearchQueryResponse getResultConformanceConceptsFromLIDRRecord(String conceptId);

    TinkarSearchQueryResponse getAllowedResultConceptsFromResultConformance(String conceptId);

    /**
     * Rebuilds the Lucene search index. This operation is asynchronous and may take some time.
     * @return A message indicating that the rebuild process has started
     */
    String rebuildSearchIndex();

    /**
     * Gets the change history for an entity, showing all version changes and field modifications.
     * This demonstrates IKE-Flow change tracking capabilities.
     * @param entityId The public ID (UUID) of the entity
     * @return ChangeHistoryResponse containing the chronology of changes
     */
    ChangeHistoryResponse getChangeHistory(String entityId);

    /**
     * Creates a sample semantic modification on an existing concept to demonstrate change tracking.
     * This creates a new comment/annotation semantic on the specified concept.
     * @param conceptId The public ID (UUID) of the concept to annotate
     * @param comment The comment text to add as an annotation
     * @return ChangeHistoryResponse showing the change that was made
     */
    ChangeHistoryResponse createSampleChange(String conceptId, String comment);

    /**
     * Gets all comment semantics attached to a concept.
     * @param conceptId The public ID (UUID) of the concept
     * @return ConceptSemanticsResponse containing all comments for this concept
     */
    ConceptSemanticsResponse getConceptComments(String conceptId);

    /**
     * Gets all semantics of any pattern attached to a concept.
     * @param conceptId The public ID (UUID) of the concept
     * @return ConceptSemanticsResponse containing all semantics for this concept
     */
    ConceptSemanticsResponse getConceptSemantics(String conceptId);

    /**
     * Gets comprehensive change history for a concept including all attached semantics.
     * This shows changes to the concept itself AND changes to all comments, descriptions, etc.
     * @param conceptId The public ID (UUID) of the concept
     * @return ConceptChangeHistoryResponse containing the full change history
     */
    ConceptChangeHistoryResponse getConceptChangeHistory(String conceptId);
}
