package edu.hust.search.query;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * Data Transfer Object tổng hợp kết quả tìm kiếm kèm thông tin phân trang và thời gian thực thi.
 */
public class SearchResponseDTO implements Serializable {
    private static final long serialVersionUID = 1L;

    private String query;
    private long totalHits;
    private int page;
    private int pageSize;
    private long executionTimeMs;
    private List<SearchResultDTO> results;

    public SearchResponseDTO() {
        this.results = Collections.emptyList();
    }

    public SearchResponseDTO(String query, long totalHits, int page, int pageSize, long executionTimeMs, List<SearchResultDTO> results) {
        this.query = query;
        this.totalHits = totalHits;
        this.page = page;
        this.pageSize = pageSize;
        this.executionTimeMs = executionTimeMs;
        this.results = results != null ? results : Collections.emptyList();
    }

    public String getQuery() {
        return query;
    }

    public void setQuery(String query) {
        this.query = query;
    }

    public long getTotalHits() {
        return totalHits;
    }

    public void setTotalHits(long totalHits) {
        this.totalHits = totalHits;
    }

    public int getPage() {
        return page;
    }

    public void setPage(int page) {
        this.page = page;
    }

    public int getPageSize() {
        return pageSize;
    }

    public void setPageSize(int pageSize) {
        this.pageSize = pageSize;
    }

    public long getExecutionTimeMs() {
        return executionTimeMs;
    }

    public void setExecutionTimeMs(long executionTimeMs) {
        this.executionTimeMs = executionTimeMs;
    }

    public List<SearchResultDTO> getResults() {
        return results;
    }

    public void setResults(List<SearchResultDTO> results) {
        this.results = results != null ? results : Collections.emptyList();
    }
}
