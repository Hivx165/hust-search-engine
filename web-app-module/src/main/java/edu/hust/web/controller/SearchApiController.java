package edu.hust.web.controller;

import edu.hust.search.query.SearchResponseDTO;
import edu.hust.web.dto.SearchResponse;
import edu.hust.web.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchApiController {

    private final SearchService searchService;

    public SearchApiController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/search")
    public SearchResponse search(
            @RequestParam(name = "keyword", required = false, defaultValue = "") String keyword,
            @RequestParam(name = "subdomain", required = false, defaultValue = "") String subdomain,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size) {
        long startTime = System.currentTimeMillis();

        SearchResponseDTO searchResponse = searchService.search(keyword, subdomain, page, size);

        long processingTimeMs = System.currentTimeMillis() - startTime;

        int calculatedTotalPages = (int) Math.ceil((double) searchResponse.getTotalHits() / size);

        return new SearchResponse(
                keyword,
                (int) searchResponse.getTotalHits(),
                page,
                calculatedTotalPages,
                processingTimeMs,
                searchResponse.getResults());
    }
}