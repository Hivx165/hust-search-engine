package edu.hust.web.service;

import edu.hust.search.index.LuceneIndexManager;
import edu.hust.search.query.LuceneSearchService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SearchServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void doesNotTurnLuceneQueryParseErrorsIntoEmptyResults() throws IOException {
        try (LuceneIndexManager indexManager =
                     new LuceneIndexManager(tempDir.resolve("lucene-index"))) {
            SearchService searchService =
                    new SearchService(new LuceneSearchService(indexManager));

            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> searchService.search("title:(", null, 1, 10));

            assertTrue(error.getMessage().contains("Lucene search failed"));
            assertTrue(error.getCause() instanceof org.apache.lucene.queryparser.classic.ParseException);
        }
    }

    @Test
    void doesNotTurnUnexpectedSearchFailuresIntoEmptyResults() {
        LuceneSearchService failingSearchService = new LuceneSearchService(null) {
            @Override
            public edu.hust.search.query.PagedSearchResponse doSearch(
                    String keyword, String subdomain, int page, int size) {
                throw new IllegalStateException("index unavailable");
            }
        };
        SearchService searchService = new SearchService(failingSearchService);

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> searchService.search("keyword", null, 1, 10));

        assertTrue(error.getMessage().contains("index unavailable"));
    }
}
