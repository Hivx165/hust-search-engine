package edu.hust.web.dto;

import edu.hust.search.query.SearchResultDTO;
import java.util.List;

public record SearchResponse(
        String keyword,
        int totalResults,
        int currentPage,
        int totalPages,
        long processingTimeMs,
        List<SearchResultDTO> results) {
}