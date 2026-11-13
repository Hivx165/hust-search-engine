package edu.hust.search.query;

/**
 * Lớp chứa dữ liệu kết quả tìm kiếm trả về cho Web App hiển thị.
 * Dùng Record của Java 17 để tự động sinh constructor và getter.
 */
public record SearchResultDTO(
        String title,
        String url,
        String snippet,
        float score) {
}