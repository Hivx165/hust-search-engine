package edu.hust.web.dto;

import java.util.List;

public record SearchResponse(
    String keyword,
    int totalResults,
    int currentPage,
    int totalPages,
    long processingTimeMs,
    List<DocumentDto> results
) {}