package edu.hust.web.dto;

public record SearchRequest(
    String keyword,
    String subdomain,
    Integer page,
    Integer size
) {
    // Compact constructor để chuẩn hóa dữ liệu mặc định
    public SearchRequest {
        if (keyword == null) keyword = "";
        if (subdomain == null) subdomain = "";
        if (page == null || page < 1) page = 1;
        if (size == null || size < 1) size = 10;
    }
}