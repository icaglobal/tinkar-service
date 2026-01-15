/*
 * Copyright © 2015 Integrated Knowledge Management (support@ikm.dev)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.ikm.tinkar.provider.search;

import dev.ikm.tinkar.common.service.PrimitiveDataSearchResult;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.highlight.InvalidTokenOffsetsException;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for search functionality that wraps Searcher and Indexer.
 * This provides a unified interface for search operations.
 */
public interface SearchService {
    /**
     * Performs a search query and returns results.
     * 
     * @param queryString The search query string
     * @param maxResultSize Maximum number of results to return
     * @return Array of search results
     * @throws ParseException If the query cannot be parsed
     * @throws IOException If there's an I/O error
     * @throws InvalidTokenOffsetsException If there's an error with token offsets
     */
    PrimitiveDataSearchResult[] search(String queryString, int maxResultSize) 
            throws ParseException, IOException, InvalidTokenOffsetsException;
    
    /**
     * Indexes an object (typically an Entity).
     * 
     * @param sourceObject The object to index
     */
    void index(Object sourceObject);
    
    /**
     * Recreates the Lucene index.
     * 
     * @return CompletableFuture that completes when the index is recreated
     */
    CompletableFuture<Void> recreateIndex();
}
