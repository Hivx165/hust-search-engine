package edu.hust.search.query;

import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import edu.hust.search.index.DocumentSchema;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.apache.lucene.store.Directory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LuceneSearchServiceTest {

    private Directory directory;
    private LuceneSearchService searchService;
    private VietnameseLuceneAnalyzer analyzer;

    @BeforeEach
    void setUp() throws Exception {
        directory = new ByteBuffersDirectory();
        analyzer = new VietnameseLuceneAnalyzer();

        // Nạp các tài liệu mẫu vào bộ nhớ để kiểm thử
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        try (IndexWriter writer = new IndexWriter(directory, config)) {
            // Doc 1: Có từ khóa "học bổng" ở cả Tiêu đề và Nội dung
            writer.addDocument(DocumentSchema.createDocument(
                    "doc1",
                    "https://ctsv.hust.edu.vn/hoc-bong-k68",
                    "Thông báo xét cấp học bổng khuyến khích kỳ 2",
                    "Phòng Công tác Sinh viên công bố danh sách nhận học bổng khuyến khích cho sinh viên K68 đạt thành tích xuất sắc.",
                    "ctsv.hust.edu.vn",
                    1700000000L,
                    "HTML",
                    "hash1"
            ));

            // Doc 2: Chỉ có từ khóa "học bổng" trong Nội dung (Title KHÔNG có)
            writer.addDocument(DocumentSchema.createDocument(
                    "doc2",
                    "https://ts.hust.edu.vn/tuyen-sinh-2024",
                    "Đề án tuyển sinh đại học chính quy Bách Khoa",
                    "Học phí hợp lý, nhiều chính sách học bổng hỗ trợ cho tân sinh viên tài năng.",
                    "ts.hust.edu.vn",
                    1700001000L,
                    "HTML",
                    "hash2"
            ));

            // Doc 3: Đồ án tốt nghiệp tại viện CNTT (SOICT)
            writer.addDocument(DocumentSchema.createDocument(
                    "doc3",
                    "https://soict.hust.edu.vn/do-an-tot-nghiep",
                    "Kế hoạch bảo vệ đồ án tốt nghiệp sinh viên SOICT",
                    "Viện Công nghệ Thông tin và Truyền thông thông báo lịch nộp đề cương và phân công giảng viên hướng dẫn đồ án tốt nghiệp.",
                    "soict.hust.edu.vn",
                    1700002000L,
                    "HTML",
                    "hash3"
            ));

            // Doc 4: Đăng ký tín chỉ tại SAMI
            writer.addDocument(DocumentSchema.createDocument(
                    "doc4",
                    "https://sami.hust.edu.vn/tin-chi",
                    "Thời khóa biểu và đăng ký học phần viện SAMI",
                    "Sinh viên theo dõi danh sách lớp mở và hạn cuối đăng ký học phần trên hệ thống quản lý đào tạo.",
                    "sami.hust.edu.vn",
                    1700003000L,
                    "HTML",
                    "hash4"
            ));
        }

        searchService = new LuceneSearchService(directory, false, analyzer);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (searchService != null) {
            searchService.close();
        }
        if (directory != null) {
            directory.close();
        }
        if (analyzer != null) {
            analyzer.close();
        }
    }

    @Test
    @DisplayName("Kiểm tra tìm kiếm cơ bản trả về đúng tài liệu")
    void testBasicSearch() throws Exception {
        List<SearchResultDTO> results = searchService.search("học bổng", 10);

        assertThat(results).hasSize(2);
        List<String> ids = results.stream().map(SearchResultDTO::getId).toList();
        assertThat(ids).contains("doc1", "doc2");
    }

    @Test
    @DisplayName("Kiểm tra Field Boosting: Từ khóa ở Title phải có điểm cao hơn chỉ ở Content")
    void testTitleBoostingGivesHigherScore() throws Exception {
        List<SearchResultDTO> results = searchService.search("học bổng", 10);

        assertThat(results).isNotEmpty();
        // Doc 1 có "học bổng" ở Title + Content (được boost 2.0x ở title),
        // Doc 2 chỉ có "học bổng" ở Content -> Doc 1 phải xếp trước Doc 2 và điểm cao hơn
        SearchResultDTO first = results.get(0);
        SearchResultDTO second = results.get(1);

        assertThat(first.getId()).isEqualTo("doc1");
        assertThat(second.getId()).isEqualTo("doc2");
        assertThat(first.getScore()).isGreaterThan(second.getScore());
    }

    @Test
    @DisplayName("Kiểm tra tìm kiếm nâng cao kết hợp MUST và SHOULD")
    void testSearchAdvancedMustAndShould() throws Exception {
        // MUST có "đồ án", SHOULD có "SOICT"
        List<SearchResultDTO> results = searchService.searchAdvanced("đồ án", "soict", null, 5);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("doc3");
        assertThat(results.get(0).getScore()).isGreaterThan(0.0f);
    }

    @Test
    @DisplayName("Kiểm tra bộ lọc theo Subdomain")
    void testSubdomainFilter() throws Exception {
        // Tìm "học bổng" nhưng giới hạn subdomain = "ctsv.hust.edu.vn"
        List<SearchResultDTO> results = searchService.searchWithFilter("học bổng", "ctsv.hust.edu.vn", 10);

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getId()).isEqualTo("doc1");
        assertThat(results.get(0).getSubdomain()).isEqualTo("ctsv.hust.edu.vn");
    }

    @Test
    @DisplayName("Kiểm tra Lucene Highlighter: Bôi đậm thẻ <mark> trong Title và Snippet")
    void testHighlightingInTitleAndSnippet() throws Exception {
        List<SearchResultDTO> results = searchService.search("học bổng", 5);

        assertThat(results).isNotEmpty();
        SearchResultDTO doc1 = results.stream()
                .filter(r -> "doc1".equals(r.getId()))
                .findFirst()
                .orElseThrow();

        // Title phải có highlight <mark>...</mark>
        assertThat(doc1.getTitle()).contains("<mark>").contains("</mark>");

        // Snippet phải có highlight <mark>...</mark>
        assertThat(doc1.getSnippet()).contains("<mark>").contains("</mark>");
    }

    @Test
    @DisplayName("Kiểm tra tìm kiếm phân trang (Paged Search)")
    void testPagedSearch() throws Exception {
        SearchResponseDTO response = searchService.searchPaged("học bổng", null, 1, 1);

        assertThat(response.getTotalHits()).isEqualTo(2);
        assertThat(response.getPage()).isEqualTo(1);
        assertThat(response.getPageSize()).isEqualTo(1);
        assertThat(response.getResults()).hasSize(1);
        assertThat(response.getResults().get(0).getId()).isEqualTo("doc1");

        // Trang 2
        SearchResponseDTO page2 = searchService.searchPaged("học bổng", null, 2, 1);
        assertThat(page2.getResults()).hasSize(1);
        assertThat(page2.getResults().get(0).getId()).isEqualTo("doc2");
    }

    @Test
    @DisplayName("Kiểm tra thời gian phản hồi tìm kiếm nhanh (< 100ms)")
    void testSearchPerformance() throws Exception {
        SearchResponseDTO response = searchService.searchPaged("đồ án tốt nghiệp", null, 1, 10);
        assertThat(response.getExecutionTimeMs()).isLessThan(100L);
    }
}
