# Phần việc thành viên 4: Lõi tìm kiếm, BooleanQuery và xếp hạng

Yêu cầu: JDK 17+, Maven 3.9+ (hoặc wrapper `./mvnw` sẵn có). Từ thư mục gốc dự án:

```powershell
mvn -pl lucene-core-module -am clean test
```

Hoặc chạy nhanh bằng Maven wrapper không cần cài Maven cục bộ:

```powershell
# Trên Linux / macOS:
./mvnw test -pl lucene-core-module

# Trên Windows PowerShell:
.\mvnw.cmd test -pl lucene-core-module
```

Module thực hiện toàn bộ chức năng xử lý truy vấn văn bản, tạo câu truy vấn phức hợp `BooleanQuery` (MUST/SHOULD), tính điểm xếp hạng theo mô hình **BM25**, gán trọng số ưu tiên trường Tiêu đề (**Title Boosting**) và tự động trích đoạn bôi đậm từ khóa bằng **Lucene Highlighter**.

---

## Kiến trúc lõi tìm kiếm và cơ chế xếp hạng

Lớp trung tâm là [`LuceneSearchService.java`](src/main/java/edu/hust/search/query/LuceneSearchService.java), mở `Directory` (trên đĩa qua `FSDirectory` hoặc trong RAM qua `ByteBuffersDirectory`), quản lý `IndexReader` và `IndexSearcher`.

- **Thuật toán xếp hạng:** Cấu hình mặc định sử dụng `BM25Similarity`. Điểm số $Score(D, Q)$ của từng tài liệu được tính toán dựa trên tần suất từ (TF), nghịch đảo tần suất tài liệu (IDF) và chuẩn hóa độ dài văn bản của Lucene.
- **Làm mới chỉ mục (Index Reloading):** Phương thức `refreshReader()` kiểm tra và mở phiên bản mới của chỉ mục qua `DirectoryReader.openIfChanged(...)`, giúp hệ thống tìm thấy bài viết mới cào mà không cần khởi động lại tiến trình chạy ngầm.
- **Tìm kiếm phân trang (`searchPaged`):** Nhận `queryText`, `subdomain`, `page` và `pageSize`. Phương thức tính `topK = page * pageSize`, thực thi truy vấn qua `searcher.search(query, topK)`, cắt mảng `ScoreDoc[]` theo khoảng trang và đóng gói vào [`SearchResponseDTO.java`](src/main/java/edu/hust/search/query/SearchResponseDTO.java) gồm tổng số tài liệu khớp (`totalHits`), danh sách kết quả và thời gian thực thi mili-giây.

---

## Cơ chế truy vấn BooleanQuery và Field Boosting

Lớp [`QueryBuilderHelper.java`](src/main/java/edu/hust/search/query/QueryBuilderHelper.java) chuyển đổi chuỗi từ khóa của người dùng thành cây truy vấn `Query` của Lucene:

- **Tách từ và lọc từ dừng:** Dùng `VietnameseLuceneAnalyzer` để trích xuất token. Các từ dừng phổ biến (`và`, `là`, `của`, `các`, `những`...) tự động bị loại bỏ để giảm nhiễu.
- **Logic MUST và SHOULD:**
  - `Occur.MUST`: Áp dụng cho các từ khóa bắt buộc phải xuất hiện. Khi tìm kiếm thông thường (`matchAllTerms = true`), mỗi từ khóa bắt buộc phải có mặt trong bài viết (ở Tiêu đề HOẶC Nội dung).
  - `Occur.SHOULD`: Áp dụng cho các từ khóa phụ trợ trong tìm kiếm nâng cao (`searchAdvanced(mustKeyword, shouldKeyword)`). Nếu tài liệu chứa thêm từ khóa này, điểm xếp hạng sẽ được cộng thêm vào tổng điểm BM25.
  - `Occur.FILTER`: Khi có tham số `subdomain` (ví dụ `soict.hust.edu.vn`), bộ lọc `TermQuery` được gắn cờ `FILTER`. Điều kiện này chỉ kiểm tra đúng/sai mà không can thiệp làm méo mó điểm số BM25.
- **Trọng số Boosting đa trường:** Khớp từ khóa trong trường `title` được gán trọng số gấp đôi so với trường `content`:
  $$Boost_{title} = 2.0, \quad Boost_{content} = 1.0$$
  Tài liệu có từ khóa xuất hiện ngay ở tiêu đề luôn được đẩy lên các vị trí đầu tiên trong danh sách trả về.

---

## Bôi đậm từ khóa và trích đoạn (Lucene Highlighter)

Tìm kiếm không trả về toàn bộ nội dung thô (có thể dài hàng chục trang) mà sử dụng gói `org.apache.lucene.search.highlight`:

- `SimpleHTMLFormatter`: Bọc từ khóa tìm thấy trong cặp thẻ `<mark>` và `</mark>`.
- `QueryScorer`: Chấm điểm các đoạn văn dựa trên câu truy vấn gốc.
- `SimpleSpanFragmenter`: Chia nhỏ văn bản thành các đoạn trích có độ dài tối đa 160 ký tự xung quanh vị trí xuất hiện từ khóa.
- **Bôi đậm đồng thời cả hai trường:**
  - Tiêu đề (`title`): Giữ nguyên câu tiêu đề và bôi đậm từ khóa khớp.
  - Trích đoạn (`snippet`): Lấy đoạn trích có điểm liên quan cao nhất từ `content`. Nếu bài viết ngắn hoặc từ khóa chỉ nằm ở tiêu đề, hệ thống dùng cơ chế fallback tự động cắt 160 ký tự đầu của nội dung bài viết để giao diện không bị trống.

---

## Dữ liệu và mã nguồn bàn giao cho thành viên 3

Thành viên 3 phụ trách phần **Lập chỉ mục (Index)**. Để module tìm kiếm hoạt động đồng bộ với module ghi chỉ mục, các thành phần dùng chung đã được chuẩn hóa sẵn trong module này:

1. **[`DocumentSchema.java`](src/main/java/edu/hust/search/index/DocumentSchema.java):** Định nghĩa tên trường và kiểu dữ liệu chuẩn:
   - `DocumentSchema.FIELD_ID` (`"id"`): `StringField` (Stored) - Mã băm bài viết.
   - `DocumentSchema.FIELD_URL` (`"url"`): `StoredField` - Đường dẫn bài viết gốc.
   - `DocumentSchema.FIELD_TITLE` (`"title"`): `TextField` (Stored) - Tiêu đề bài viết.
   - `DocumentSchema.FIELD_CONTENT` (`"content"`): `TextField` (Stored) - Nội dung sạch đã bóc tách từ HTML/PDF.
   - `DocumentSchema.FIELD_SUBDOMAIN` (`"subdomain"`): `StringField` (Stored) - Ví dụ `soict.hust.edu.vn`.
   - `DocumentSchema.FIELD_CRAWLED_DATE` (`"crawled_date"`): `LongPoint` và `StoredField` - Timestamp thời điểm cào.
   - `DocumentSchema.FIELD_FILE_TYPE` (`"file_type"`): `StringField` (Stored) - HTML, PDF, DOCX.
2. **[`VietnameseLuceneAnalyzer.java`](src/main/java/edu/hust/search/analyzer/VietnameseLuceneAnalyzer.java):** Kế thừa `Analyzer`, cấu hình `StandardTokenizer`, `LowerCaseFilter` và tự nạp từ dừng từ file `src/main/resources/vietnamese_stopwords.txt`.

### Code mẫu thành viên 3 sử dụng để hoàn thiện `LuceneIndexManager.java`:

```java
package edu.hust.search.index;

import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Paths;

public class LuceneIndexManager implements Closeable {
    private final IndexWriter writer;

    public LuceneIndexManager(String indexPath) throws IOException {
        Directory dir = FSDirectory.open(Paths.get(indexPath));
        VietnameseLuceneAnalyzer analyzer = new VietnameseLuceneAnalyzer();
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        // Tối ưu hóa bộ đệm RAM 256MB theo yêu cầu đề bài
        config.setRAMBufferSizeMB(256.0);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        this.writer = new IndexWriter(dir, config);
    }

    public void indexArticle(String id, String url, String title, String content, 
                             String subdomain, long crawledDate, String fileType, String contentHash) throws IOException {
        Document doc = DocumentSchema.createDocument(id, url, title, content, subdomain, crawledDate, fileType, contentHash);
        writer.updateDocument(new Term(DocumentSchema.FIELD_ID, id), doc);
    }

    public void commit() throws IOException {
        writer.commit();
    }

    @Override
    public void close() throws IOException {
        writer.close();
    }
}
```

### Việc còn lại thành viên 3 phải làm:
- Viết pipeline đọc dữ liệu từ `parser-module` (TV2) hoặc file JSON/SQLite từ `crawler-module` (TV1).
- Nạp toàn bộ dữ liệu vào `IndexWriter` để sinh ra thư mục Index chứa tối thiểu **1.000 bài viết** trên đĩa cục bộ (ví dụ: `storage/lucene_index`).

---

## Dữ liệu và mã nguồn bàn giao cho thành viên 5

Thành viên 5 phụ trách **Web UI** và **Đo đạc chỉ số MAP**. Dữ liệu đầu ra của tìm kiếm đã được đóng gói hoàn chỉnh:

1. **[`SearchResultDTO.java`](src/main/java/edu/hust/search/query/SearchResultDTO.java):** Đối tượng một kết quả (id, url, title, snippet có thẻ `<mark>`, score, subdomain, crawledDate, fileType).
2. **[`SearchResponseDTO.java`](src/main/java/edu/hust/search/query/SearchResponseDTO.java):** Đối tượng bọc phân trang (query, totalHits, page, pageSize, executionTimeMs, results).

### Code mẫu thành viên 5 tích hợp vào Spring Boot REST Controller:

```java
package edu.hust.web.controller;

import edu.hust.search.query.LuceneSearchService;
import edu.hust.search.query.SearchResponseDTO;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@CrossOrigin(origins = "*")
public class SearchApiController {

    private final LuceneSearchService searchService;

    // Truyền đường dẫn thư mục Index do TV3 tạo ra
    public SearchApiController() throws Exception {
        this.searchService = new LuceneSearchService("storage/lucene_index");
    }

    @GetMapping("/search")
    public SearchResponseDTO search(
            @RequestParam(name = "q", defaultValue = "") String keyword,
            @RequestParam(name = "subdomain", required = false) String subdomain,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "10") int size
    ) throws Exception {
        return searchService.searchPaged(keyword, subdomain, page, size);
    }
}
```

### Code mẫu thành viên 5 dùng để đo chỉ số MAP (`MapCalculator.java`):

```java
// Lấy Top-50 kết quả cho 1 câu truy vấn thử nghiệm
List<SearchResultDTO> results = searchService.search(queryText, 50);
List<String> retrievedDocIds = results.stream().map(SearchResultDTO::getId).toList();

// Tính Average Precision (AP) so với tập nhãn ground truth
double ap = MapCalculator.calculateAveragePrecision(retrievedDocIds, groundTruthRelevantDocIds);
```

### Việc còn lại thành viên 5 phải làm:
- Thiết kế Web UI trong `web-app-module/src/main/resources/static/` có ô tìm kiếm, dropdown chọn subdomain, phân trang và render thẻ `<mark>`.
- Soạn tập 25 câu truy vấn thử nghiệm ($|Q| \sim 3$) và file nhãn `ground_truth_relevance.csv`.
- Chạy module `evaluation-module` đo MAP và so sánh giữa 2 trường hợp: có Title Boost ($Boost = 2.0$) và không có Title Boost ($Boost = 1.0$) để lấy số liệu làm báo cáo.

---

## Kiểm chứng và giới hạn nghiệm thu

Bộ kiểm thử tự động gồm 13 test case độc lập:

- **[`QueryBuilderHelperTest`](src/test/java/edu/hust/search/query/QueryBuilderHelperTest.java):**
  - Tách token và loại bỏ stopwords tiếng Việt.
  - Tạo `BooleanQuery` dạng MUST (`matchAllTerms = true`) và SHOULD (`matchAllTerms = false`).
  - Gắn điều kiện `FILTER` cho subdomain.
  - Tạo câu truy vấn nâng cao kết hợp MUST và SHOULD có Boost.
  - Xử lý truy vấn rỗng (`MatchAllDocsQuery`).
- **[`LuceneSearchServiceTest`](src/test/java/edu/hust/search/query/LuceneSearchServiceTest.java):**
  - Khởi tạo index mẫu trong RAM (`ByteBuffersDirectory`) với 4 bài viết mẫu của các subdomain khác nhau (`ctsv`, `ts`, `soict`, `sami`).
  - **Kiểm chứng Title Boosting:** Bài viết có từ khóa ở Tiêu đề đạt điểm BM25 cao hơn và được xếp trên bài viết chỉ có từ khóa ở Nội dung.
  - **Kiểm chứng MUST & SHOULD:** Kiểm tra kết quả lọc đúng bài viết thỏa mãn cả hai điều kiện.
  - **Kiểm chứng Lọc Subdomain:** Giới hạn chỉ trả về kết quả thuộc đúng phân vùng yêu cầu.
  - **Kiểm chứng Highlighter:** Đoạn trích và tiêu đề có chứa cặp thẻ `<mark>` và `</mark>`.
  - **Kiểm chứng Phân trang:** Kiểm tra `page`, `pageSize`, `totalHits` qua các trang 1 và 2.
  - **Kiểm chứng Thời gian phản hồi:** Đảm bảo thực thi dưới 100ms.

Tất cả kiểm thử hiện dùng `ByteBuffersDirectory` trong bộ nhớ, không phụ thuộc vào việc có mạng hay ổ đĩa. Khi tích hợp toàn hệ thống, chỉ cần truyền đường dẫn thư mục `FSDirectory` của TV3 vào constructor `LuceneSearchService(String indexPath)`.
