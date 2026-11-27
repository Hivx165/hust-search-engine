package edu.hust.web.service;

import edu.hust.search.index.LuceneIndexManager;
import edu.hust.search.query.LuceneSearchService;
import edu.hust.search.query.SearchResponseDTO;
import edu.hust.search.query.SearchResultDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void returnsEmptyResponseOnInvalidLuceneQuery() throws IOException {
        Path indexDir = tempDir.resolve("lucene-index");
        try (LuceneIndexManager ignored = new LuceneIndexManager(indexDir)) {
            LuceneSearchService luceneSearchService = new LuceneSearchService(indexDir);
            SearchService searchService = new SearchService(luceneSearchService);

            SearchResponseDTO response = searchService.search("title:(", null, 1, 10);

            assertEquals(0L, response.getTotalHits());
            assertTrue(response.getResults().isEmpty());
        }
    }

    @Test
    void returnsEmptyResponseWhenSearchServiceFails() throws IOException {
        Path indexDir = tempDir.resolve("failing-index");
        LuceneSearchService failingSearchService = new LuceneSearchService(indexDir) {
            @Override
            public List<SearchResultDTO> search(String queryText, int topK) {
                throw new IllegalStateException("index unavailable");
            }
        };
        SearchService searchService = new SearchService(failingSearchService);

        SearchResponseDTO response = searchService.search("keyword", null, 1, 10);

        assertEquals(0L, response.getTotalHits());
        assertTrue(response.getResults().isEmpty());
    }
}