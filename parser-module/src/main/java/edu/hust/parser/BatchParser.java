package edu.hust.parser;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class BatchParser {

    // 1. Đối tượng DTO lưu trữ dữ liệu sạch cuối cùng để truyền cho Lucene
    public static class ParsedDocumentDTO {
        public String url;
        public String title;
        public String subdomain;
        public String cleanContent;

        public ParsedDocumentDTO(String url, String title, String subdomain, String cleanContent) {
            this.url = url;
            this.title = title;
            this.subdomain = subdomain;
            this.cleanContent = cleanContent;
        }
    }

    /**
     * Hàm xử lý hàng loạt: Đọc từ Database, chạy qua toàn bộ Pipeline và trả về danh sách dữ liệu sạch
     * @param dbPath Đường dẫn tới file SQLite (vd: "storage/crawler.db")
     */
    public static List<ParsedDocumentDTO> parseAllFromDatabase(String dbPath) {
        List<ParsedDocumentDTO> results = new ArrayList<>();
        
        try {
            // TỐI ƯU HÓA: Chỉ khởi tạo VnCoreNLP và StopwordFilter MỘT LẦN duy nhất ở ngoài vòng lặp 
            // (VnCoreNLP tải model vào RAM khá nặng, không được new trong vòng lặp)
            System.out.println("[INFO] Đang khởi tạo bộ model tách từ Tiếng Việt VnCoreNLP...");
            VietnameseSegmenter segmenter = new VietnameseSegmenter();
            StopwordFilter stopwordFilter = new StopwordFilter();
            System.out.println("[INFO] Khởi tạo AI hoàn tất. Bắt đầu xử lý dữ liệu...");
            
            // Kết nối vào cơ sở dữ liệu SQLite
            String jdbcUrl = "jdbc:sqlite:" + dbPath;
            try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                
                // Truy vấn chỉ lấy những bản ghi đã tải file thành công (local_path không null)
                String sql = "SELECT url, title, subdomain, local_path FROM crawled_pages WHERE local_path IS NOT NULL";
                try (PreparedStatement pstmt = conn.prepareStatement(sql);
                     ResultSet rs = pstmt.executeQuery()) {
                     
                    int count = 0;
                    while (rs.next()) {
                        String url = rs.getString("url");
                        String title = rs.getString("title");
                        String subdomain = rs.getString("subdomain");
                        String localPath = rs.getString("local_path");
                        
                        // Bước 1: Trích xuất Text thô từ file HTML/PDF
                        String text = extractTextFromFile(localPath);
                        
                        if (text != null && !text.isBlank()) {
                            // Bước 2: Chuẩn hóa khoảng trắng
                            text = text.replaceAll("\\s+", " ").trim();
                            
                            // Bước 3: Tách từ Tiếng Việt
                            String segmented = segmenter.segmentText(text);
                            
                            // Bước 4: Lọc bỏ từ vô nghĩa (Stopwords)
                            String finalCleanText = stopwordFilter.removeStopwords(segmented);
                            
                            results.add(new ParsedDocumentDTO(url, title, subdomain, finalCleanText));
                            count++;
                            
                            // In log tiến độ để theo dõi
                            if (count % 10 == 0) {
                                System.out.println("[PROGRESS] Đã xử lý " + count + " bài viết...");
                            }
                        }
                    }
                    System.out.println("[SUCCESS] Hoàn tất! Tổng số bài viết sạch thu được: " + count);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return results;
    }

    /**
     * Hàm phụ trợ: Đọc file dựa vào đuôi mở rộng
     */
    private static String extractTextFromFile(String localPath) {
        try {
            Path path = Paths.get(localPath);
            if (!Files.exists(path)) return null;

            String name = path.getFileName().toString().toLowerCase();
            
            // Phân nhánh dựa theo định dạng file
            if (name.endsWith(".html")) {
                String html = Files.readString(path);
                return HtmlContentCleaner.cleanHtml(html);
                
            } else if (name.endsWith(".pdf") || name.endsWith(".docx")) {
                try (InputStream stream = Files.newInputStream(path)) {
                    return TikaDocumentExtractor.extractTextFromStream(stream);
                }
            }
        } catch (Exception e) {
            System.err.println("[ERROR] Lỗi khi trích xuất file: " + localPath + " (" + e.getMessage() + ")");
        }
        return null;
    }

    // Hàm Main để chạy thử nghiệm độc lập
    public static void main(String[] args) {
        // Trỏ đường dẫn vào file crawler.db (Chạy từ thư mục gốc của project)
        String dbPath = "storage/crawler.db"; 
        
        List<ParsedDocumentDTO> parsedDocs = parseAllFromDatabase(dbPath);
        
        // In thử thông tin bài viết đầu tiên để nghiệm thu
        if (!parsedDocs.isEmpty()) {
            System.out.println("\n=== NGHIỆM THU KẾT QUẢ ĐẦU RA ===");
            ParsedDocumentDTO doc = parsedDocs.get(0);
            System.out.println("URL       : " + doc.url);
            System.out.println("Tiêu đề   : " + doc.title);
            System.out.println("Subdomain : " + doc.subdomain);
            System.out.println("Nội dung  : " + 
                (doc.cleanContent.length() > 250 ? doc.cleanContent.substring(0, 250) + "..." : doc.cleanContent));
        }
    }
}