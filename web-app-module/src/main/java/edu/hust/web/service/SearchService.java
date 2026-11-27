package edu.hust.web.service;

import edu.hust.search.query.LuceneSearchService;
import edu.hust.search.query.SearchResponseDTO;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
public class SearchService {

    private final LuceneSearchService luceneSearchService;

    public SearchService(LuceneSearchService luceneSearchService) {
        this.luceneSearchService = luceneSearchService;
    }

    public SearchResponseDTO search(String keyword, String subdomain, int page, int size) {
        long startTime = System.currentTimeMillis();

        int validPage = Math.max(1, page);
        int validSize = size <= 0 ? 10 : Math.min(size, 100);

        if (keyword == null || keyword.isBlank()) {
            return new SearchResponseDTO(
                    keyword,
                    0L,
                    validPage,
                    validSize,
                    System.currentTimeMillis() - startTime,
                    Collections.emptyList());
        }

        try {
            return luceneSearchService.searchPaged(keyword, subdomain, validPage, validSize);
        } catch (Exception e) {
            return new SearchResponseDTO(
                    keyword,
                    0L,
                    validPage,
                    validSize,
                    System.currentTimeMillis() - startTime,
                    Collections.emptyList());
        }
    }
}