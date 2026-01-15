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
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Implementation of SearchService that wraps Searcher and Indexer.
 */
public class SearchServiceImpl implements SearchService {
    private final Indexer indexer;
    private final Searcher searcher;
    
    /**
     * Creates a SearchServiceImpl with the given index path.
     * 
     * @param indexPath The path to the Lucene index directory
     * @throws IOException If there's an error creating the indexer or searcher
     */
    public SearchServiceImpl(Path indexPath) throws IOException {
        this.indexer = new Indexer(indexPath);
        this.searcher = new Searcher();
    }
    
    /**
     * Creates a SearchServiceImpl with existing Indexer and Searcher instances.
     * 
     * @param indexer The Indexer instance
     * @param searcher The Searcher instance
     */
    public SearchServiceImpl(Indexer indexer, Searcher searcher) {
        this.indexer = indexer;
        this.searcher = searcher;
    }
    
    @Override
    public PrimitiveDataSearchResult[] search(String queryString, int maxResultSize) 
            throws ParseException, IOException, InvalidTokenOffsetsException {
        return searcher.search(queryString, maxResultSize);
    }
    
    @Override
    public void index(Object sourceObject) {
        indexer.index(sourceObject);
    }
    
    @Override
    public CompletableFuture<Void> recreateIndex() {
        // Use RecreateIndex to rebuild the Lucene index
        return CompletableFuture.runAsync(() -> {
            try {
                RecreateIndex recreateIndex = new RecreateIndex(indexer);
                recreateIndex.call();
            } catch (Exception e) {
                throw new RuntimeException("Failed to recreate index", e);
            }
        });
    }
    
    /**
     * Gets the Indexer instance.
     * 
     * @return The Indexer instance
     */
    public Indexer getIndexer() {
        return indexer;
    }
    
    /**
     * Gets the Searcher instance.
     * 
     * @return The Searcher instance
     */
    public Searcher getSearcher() {
        return searcher;
    }
}
