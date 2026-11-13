package edu.hust.search.query;

import java.util.List;

public class PagedSearchResponse {

    private long totalHits;
    private List<SearchResultDTO> results;

    public PagedSearchResponse() {
    }

    public PagedSearchResponse(long totalHits, List<SearchResultDTO> results) {
        this.totalHits = totalHits;
        this.results = results;
    }

    public long getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(long totalHits) {
        this.totalHits = totalHits;
    }

    public List<SearchResultDTO> getResults() {
        return results;
    }

    public void setResults(List<SearchResultDTO> results) {
        this.results = results;
    }
}