package edu.hust.web.service;

import edu.hust.search.query.LuceneSearchService;
import edu.hust.search.query.SearchResultDTO;
// Import thêm DTO Wrapper mà ta vừa tạo
import edu.hust.search.query.PagedSearchResponse;

import org.springframework.stereotype.Service;
import java.util.Collections;

@Service
public class SearchService {

    private final LuceneSearchService luceneSearchService;

    public SearchService(LuceneSearchService luceneSearchService) {
        this.luceneSearchService = luceneSearchService;
    }

    public PagedSearchResponse search(String keyword, String subdomain, int page, int size) {

        // Validation
        if (keyword == null || keyword.trim().isEmpty()) {
            // Fallback: Trả về đối tượng PagedSearchResponse với tổng số = 0 và danh sách
            // rỗng
            return new PagedSearchResponse(0, Collections.emptyList());
        }

        if (page < 1)
            page = 1;
        if (size <= 0)
            size = 10;
        if (size > 100)
            size = 100;

        try {
            return luceneSearchService.doSearch(keyword, subdomain, page, size);

        } catch (Exception e) {
            System.err.println("Lỗi trong quá trình tìm kiếm Lucene: " + e.getMessage());
            e.printStackTrace();
            return new PagedSearchResponse(0, Collections.emptyList());
        }
    }
}