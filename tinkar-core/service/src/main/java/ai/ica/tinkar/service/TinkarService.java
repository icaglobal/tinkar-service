package ai.ica.tinkar.service;

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
}
