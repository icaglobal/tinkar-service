package ai.ica.tinkar.service;

import ai.ica.tinkar.dto.TinkarSearchQueryResponse;

public interface TinkarService {
    TinkarSearchQueryResponse search(String query);

    TinkarSearchQueryResponse conceptSearch(String query, Integer maxResults);

    TinkarSearchQueryResponse getEntity(String query);

    TinkarSearchQueryResponse getChildConcepts(String query);

    TinkarSearchQueryResponse getDescendantConcepts(String query);

    TinkarSearchQueryResponse getLIDRRecordConceptsFromTestKit(String query);

    TinkarSearchQueryResponse getResultConformanceConceptsFromLIDRRecord(String query);

    TinkarSearchQueryResponse getAllowedResultConceptsFromResultConformance(String query);

    /**
     * Rebuilds the Lucene search index. This operation is asynchronous and may take some time.
     * @return A message indicating that the rebuild process has started
     */
    String rebuildSearchIndex();
}
