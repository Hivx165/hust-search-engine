package edu.hust.web.service;

import edu.hust.web.dto.DocumentDto;
import edu.hust.web.dto.SearchRequest;
import edu.hust.web.dto.SearchResponse;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchService {

    public SearchResponse search(SearchRequest request) {
        long startTime = System.currentTimeMillis();

        // Lấy các giá trị đã được chuẩn hóa từ Request
        String keyword = request.keyword();
        int page = request.page();
        int size = request.size();

        List<DocumentDto> allMockResults = generateMockData(keyword);
        int totalResults = allMockResults.size();
        
        int totalPages = (int) Math.ceil((double) totalResults / size);
        
        if (page > totalPages && totalPages > 0) {
            page = totalPages;
        }

        int startIndex = (page - 1) * size;
        
        List<DocumentDto> pagedResults = new ArrayList<>();
        if (startIndex < totalResults) {
            int endIndex = Math.min(startIndex + size, totalResults);
            pagedResults = allMockResults.subList(startIndex, endIndex);
        }

        long processingTime = System.currentTimeMillis() - startTime;

        return new SearchResponse(keyword, totalResults, page, totalPages, processingTime, pagedResults);
    }

    private List<DocumentDto> generateMockData(String keyword) {
        List<DocumentDto> list = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            list.add(new DocumentDto(
                "Tài liệu " + i + " về: " + keyword,
                "https://hust.edu.vn/doc/" + i,
                "Đây là trích đoạn nội dung...",
                (float) (Math.random() * 10)
            ));
        }
        return list;
    }
}