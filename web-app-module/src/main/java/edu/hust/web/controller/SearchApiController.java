package edu.hust.web.controller;

import edu.hust.web.dto.SearchRequest;
import edu.hust.web.dto.SearchResponse;
import edu.hust.web.service.SearchService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SearchApiController {

    private final SearchService searchService;

    public SearchApiController(SearchService searchService) {
        this.searchService = searchService;
    }

    @GetMapping("/api/v1/search")
    public SearchResponse search(SearchRequest request) {
        return searchService.search(request);
    }
}