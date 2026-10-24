## BÀI TẬP LỚN MÔN HỌC: TÌM KIẾM THÔNG TIN (INFORMATION RETRIEVAL)

### ĐỀ TÀI: HỆ THỐNG THU THẬP VÀ TÌM KIẾM THÔNG TIN HỆ SINH THÁI HUST (JAVA & APACHE LUCENE)

---

# MỤC LỤC

1. [TỔNG QUAN DỰ ÁN & BỐI CẢNH](#1-tổng-quan-dự-án--bối-cảnh)
2. [GIẢI THÍCH KHÁI NIỆM & CÔNG NGHỆ CỐT LÕI (DÀNH CHO NGƯỜI MỚI BẮT ĐẦU)](#2-giải-thích-khái-niệm--công-nghệ-cốt-lõi-dành-cho-người-mới-bắt-đầu)
3. [KIẾN TRÚC HỆ THỐNG TOÀN DIỆN (SYSTEM ARCHITECTURE)](#3-kiến-trúc-hệ-thống-toàn-diện-system-architecture)
4. [ĐẶC TẢ CHI TIẾT TỪNG MODULE & CODE MẪU NỀN TẢNG](#4-đặc-tả-chi-tiết-từng-module--code-mẫu-nền-tảng)
   - 4.1. Module Crawler & Scheduler (Thu thập dữ liệu)
   - 4.2. Module Parser & Tika Extraction (Bóc tách file & Tiền xử lý)
   - 4.3. Module Lucene Core (Lập chỉ mục & Công cụ truy vấn)
   - 4.4. Module Evaluation (Đo đạc chỉ số MAP)
   - 4.5. Module Web Interface & REST API
5. [MÔ HÌNH DỮ LIỆU & GIAO THỨC TRUYỀN TẢI (DATA SCHEMAS)](#5-mô-hình-dữ-liệu--giao-thức-truyền-tải-data-schemas)
6. [PHÂN CHIA CÔNG VIỆC CHI TIẾT CHO 5 THÀNH VIÊN](#6-phân-chia-công-việc-chi-tiết-cho-5-thành-viên)
7. [KẾ HOẠCH TRIỂN KHAI TỪNG TUẦN (ROADMAP)](#7-kế-hoạch-triển-khai-từng-tuần-roadmap)
8. [TIÊU CHUẨN ĐÁNH GIÁ & CHECKLIST NGHIỆM THU](#8-tiêu-chuẩn-đánh-giá--checklist-nghiệm-thu)

---

# 1. TỔNG QUAN DỰ ÁN & BỐI CẢNH

### 1.1. Mục tiêu

Xây dựng một hệ sinh thái phần mềm hoàn chỉnh bằng **Java** thực hiện 4 công việc chính:

1. **Thu thập dữ liệu:** Tự động khám phá các subdomain thuộc trường Đại học Bách Khoa Hà Nội (`*.hust.edu.vn`), tải bài viết HTML và các tệp văn bản đính kèm (`.pdf`, `.docx`).
2. **Theo dõi cập nhật:** Có cơ chế phát hiện bài viết mới và rà soát các nội dung đã thay đổi định kỳ (Content Change Detection).
3. **Chỉ mục hóa & Tìm kiếm nâng cao:** Xây dựng Inverted Index bằng **Apache Lucene thuần**, tích hợp giải pháp tách từ tiếng Việt, cho phép tìm kiếm toàn văn (Full-text Search), kết hợp truy vấn phức hợp (BooleanQuery MUST/SHOULD) và tính điểm BM25 kết hợp trọng số (Boost).
4. **Đánh giá thực nghiệm:** Thực hiện thử nghiệm trên quy mô tối thiểu **1000 bài viết**, đo lường chỉ số chuẩn trong IR là **MAP (Mean Average Precision)** với tập câu truy vấn trung bình 3 từ ($|Q| \sim 3$).

### 1.2. Môi trường công nghệ

- **Ngôn ngữ:** Java 17 hoặc Java 21 (LTS).
- **Quản lý dự án & Thư viện:** Apache Maven (Multi-module).
- **Thư viện tìm kiếm:** Apache Lucene 9.x hoặc 10.x (`lucene-core`, `lucene-analysis-common`, `lucene-queryparser`).
- **Bóc tách văn bản:** Apache Tika (`tika-core`, `tika-parsers-standard-package`).
- **Thu thập dữ liệu:** Jsoup, HttpClient, hoặc Crawler4j.
- **Tách từ tiếng Việt:** VnCoreNLP / RDRsegmenter hoặc từ điển từ ghép tiếng Việt.
- **Cơ sở dữ liệu lưu trạng thái Crawler:** SQLite / H2 Database.
- **Giao diện & API:** Spring Boot (REST API) + Giao diện Web HTML5/CSS3/JavaScript.

---

# 2. GIẢI THÍCH KHÁI NIỆM & CÔNG NGHỆ CỐT LÕI (DÀNH CHO NGƯỜI MỚI BẮT ĐẦU)

Phần này giúp tất cả các thành viên nắm vững bản chất học thuật trước khi viết code:

### 2.1. Inverted Index (Chỉ mục đảo) là gì?

Thay vì tìm kiếm tuần tự bằng cách duyệt qua từng bài viết (rất chậm), Lucene sử dụng bảng chỉ mục đảo.

- Ví dụ:
  - Doc 1: "sinh viên Bách Khoa"
  - Doc 2: "sinh viên tốt nghiệp"
- Inverted Index sẽ lưu:
  - `sinh_viên` $\rightarrow$ [Doc 1, Doc 2]
  - `Bách_Khoa` $\rightarrow$ [Doc 1]
  - `tốt_nghiệp` $\rightarrow$ [Doc 2]
    Khi tìm từ khóa "Bách Khoa", hệ thống tra cứu bảng từ vựng và lấy ngay ra Doc 1 trong thời gian nano-giây.

### 2.2. Apache Tika làm nhiệm vụ gì?

Khi cào dữ liệu từ HUST, có rất nhiều thông báo hoặc đề cương nằm trong tệp `.pdf` hoặc `.docx`. Trình duyệt chỉ tải về mã nhị phân. **Apache Tika** đóng vai trò là một "chiếc máy phân tích vạn năng": nhận đầu vào là một file bất kỳ, tự nhận diện định dạng và xuất ra chuỗi ký tự thuần (Text) để Lucene có thể đem đi lập chỉ mục.

### 2.3. Tách từ tiếng Việt (Vietnamese Word Segmentation)

Tiếng Anh phân tách từ bằng dấu cách (`computer science` = 2 từ). Nhưng tiếng Việt dùng dấu cách cho cả âm tiết.

- Ví dụ: Cụm từ _"học sinh học sinh học"_ gồm các từ: `học sinh` (danh từ), `học` (động từ), `sinh học` (danh từ).
- Nếu không tách từ, Lucene sẽ tìm từng âm tiết đơn lẻ (`học`, `sinh`), dẫn đến kết quả tìm kiếm rất nhiễu.
- Sau khi qua bộ tách từ, văn bản trở thành: `học_sinh học sinh_học`. Lucene sẽ coi `học_sinh` là 1 token duy nhất.

### 2.4. BooleanQuery: MUST, SHOULD, MUST_NOT

Trong Apache Lucene:

- **`Occur.MUST` (+):** Điều kiện bắt buộc. Tài liệu PHẢI chứa từ khóa này mới được coi là kết quả hợp lệ.
- **`Occur.SHOULD` ( ):** Điều kiện tùy chọn. Tài liệu không nhất thiết phải có từ này, nhưng nếu có thì điểm số xếp hạng (Score) sẽ được cộng thêm vào.
- **`Occur.MUST_NOT` (-):** Điều kiện loại trừ. Nếu tài liệu chứa từ này, tài liệu bị loại ngay lập tức.

### 2.5. Chỉ số MAP (Mean Average Precision)

- **Precision at k ($P@k$):** Trong top $k$ tài liệu trả về, có bao nhiêu phần trăm tài liệu thực sự liên quan đến câu hỏi.
- **Average Precision (AP):** Tính trung bình các giá trị $P@k$ tại các vị trí $k$ mà tài liệu liên quan xuất hiện:
  $$AP = \frac{1}{|R|} \sum_{k=1}^{M} \Big( P@k \times rel(k) \Big)$$
  Trong đó: $|R|$ là tổng số tài liệu liên quan thực tế trong kho, $rel(k) = 1$ nếu kết quả thứ $k$ liên quan, ngược lại bằng $0$.
- **MAP (Mean Average Precision):** Trung bình cộng AP trên toàn bộ tập hợp các câu hỏi truy vấn $Q$:
  $$MAP = \frac{1}{|Q|} \sum_{q=1}^{|Q|} AP(q)$$

---

# 3. KIẾN TRÚC HỆ THỐNG TOÀN DIỆN (SYSTEM ARCHITECTURE)

Dự án được tách thành cấu trúc dự án Maven đa module (Multi-module Project) độc lập:

```text
hust-search-engine/
│
├── pom.xml                                   # Root Maven POM quản lý phiên bản
│
├── crawler-module/                           # Module 1: Cào bài viết & File
│   ├── src/main/java/edu/hust/crawler/
│   │   ├── SubdomainDiscoverer.java         # Quét danh sách các subdomain *.hust.edu.vn
│   │   ├── HustWebCrawler.java              # Trình cào web đệ quy (Jsoup / HttpClient)
│   │   ├── ChangeDetectionService.java      # Lưu SHA-256 hash phát hiện bài mới/sửa
│   │   └── CrawlerScheduler.java            # Lập lịch chạy định kỳ (Cronjob)
│   └── src/main/resources/
│       └── initial_subdomains.txt           # Danh sách các subdomain khởi tạo ban đầu
│
├── parser-module/                            # Module 2: Bóc tách file & Tiền xử lý
│   ├── src/main/java/edu/hust/parser/
│   │   ├── TikaDocumentExtractor.java       # Dùng Tika bóc tách text từ PDF, DOCX
│   │   ├── HtmlContentCleaner.java          # Bỏ thẻ rác HTML (header, footer, menu)
│   │   └── VietnameseSegmenter.java         # Bộ tách từ tiếng Việt (VnCoreNLP)
│   └── src/main/resources/
│       └── vietnamese_stopwords.txt         # Danh sách từ dừng (và, thì, là, mà...)
│
├── lucene-core-module/                       # Module 3: Lõi chỉ mục & Tìm kiếm Lucene
│   ├── src/main/java/edu/hust/search/
│   │   ├── analyzer/
│   │   │   └── VietnameseLuceneAnalyzer.java# Custom Analyzer tích hợp bộ tách từ & Stopwords
│   │   ├── index/
│   │   │   ├── DocumentSchema.java          # Định nghĩa cấu trúc Field (title, content,...)
│   │   │   └── LuceneIndexManager.java      # Ghi và cập nhật Inverted Index (IndexWriter)
│   │   └── query/
│   │       ├── LuceneSearchService.java     # IndexSearcher, tìm kiếm Top-K
│   │       ├── QueryBuilderHelper.java      # Tạo BooleanQuery (MUST / SHOULD)
│   │       └── SearchResultDTO.java         # Dữ liệu trả về (Title, Snippet, Score)
│
├── evaluation-module/                        # Module 4: Thực nghiệm đo lường MAP
│   ├── src/main/java/edu/hust/evaluation/
│   │   ├── BenchmarkRunner.java             # Nạp tập truy vấn và chạy tự động
│   │   ├── MapCalculator.java               # Công thức tính Precision@k, AP, MAP
│   │   └── EvaluationReportExporter.java    # Xuất kết quả ra file Markdown/CSV
│   └── src/main/resources/
│       ├── test_queries.json                # Tập các query thử nghiệm (|Q| ~ 3)
│       └── ground_truth_relevance.csv       # File gán nhãn đúng/sai thực tế
│
└── web-app-module/                           # Module 5: Giao diện Web & REST API
    ├── src/main/java/edu/hust/web/
    │   ├── SearchEngineApplication.java     # Spring Boot Main Application
    │   └── controller/
    │       ├── SearchApiController.java     # REST API cho ô tìm kiếm
    │       └── AdminCrawlerController.java  # API kiểm soát tiến trình cào
    └── src/main/resources/
        ├── application.yml
        └── static/                          # Web UI (HTML, CSS, JS)
            ├── index.html                   # Giao diện tìm kiếm phong cách Google
            ├── app.js                       # Gọi AJAX hiển thị kết quả và highlight
            └── style.css
```

---

# 4. ĐẶC TẢ CHI TIẾT TỪNG MODULE & CODE MẪU NỀN TẢNG

## 4.1. Module Crawler & Scheduler (Thu thập dữ liệu)

### Nhiệm vụ chi tiết:

1. **Quét subdomain:** Từ tên miền gốc `hust.edu.vn`, tự động trích xuất các link nội bộ có định dạng `https://[subdomain].hust.edu.vn`. Ví dụ: `soict.hust.edu.vn`, `sami.hust.edu.vn`, `chemeng.hust.edu.vn`, `cfl.hust.edu.vn`.
2. **Cào dữ liệu (Crawler):**
   - Tải nội dung trang web chính (`.html`).
   - Tìm kiếm các tệp đính kèm trong bài viết (`.pdf`, `.docx`, `.xlsx`) và tải về thư mục tạm `storage/raw_files/`.
3. **Phát hiện nội dung mới & cập nhật định kỳ:**
   - Tính mã băm `SHA-256` của nội dung bài viết.
   - Lưu thông tin vào SQLite:
     ```sql
     CREATE TABLE crawled_pages (
         url TEXT PRIMARY KEY,
         content_hash TEXT,
         last_scraped_at TIMESTAMP,
         status TEXT
     );
     ```
   - Khi quét lại, nếu URL chưa từng có $\rightarrow$ Bài viết mới. Nếu URL đã có nhưng `content_hash` thay đổi $\rightarrow$ Bài viết đã cập nhật.

### Code mẫu phát hiện thay đổi nội dung (Java):

```java
package edu.hust.crawler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ChangeDetectionService {
    public static String computeSHA256(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Lỗi tính hash nội dung", e);
        }
    }
}
```

---

## 4.2. Module Parser & Tika Extraction (Bóc tách file & Tiền xử lý)

### Nhiệm vụ chi tiết:

1. **Bóc tách văn bản tệp đính kèm (Apache Tika):**
   Đọc nội dung văn bản thuần từ bất kỳ tệp PDF, Word nào được tải về từ hệ thống HUST.
2. **Làm sạch HTML:** Dùng Jsoup loại bỏ các thành phần rác: `<header>`, `<footer>`, `<nav>`, `<script>`, `<style>` để giữ lại đúng nội dung chính của bài viết.
3. **Tách từ tiếng Việt:** Sử dụng VnCoreNLP hoặc bộ thư viện tách từ dựa trên từ điển để gộp các từ ghép lại bằng ký tự gạch dưới `_`.
   - Ví dụ: `"học bổng khuyến khích học tập sinh viên"` $\rightarrow$ `"học_bổng khuyến_khích học_tập sinh_viên"`.

### Code mẫu bóc tách file bằng Apache Tika (Java):

```java
package edu.hust.parser;

import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.sax.BodyContentHandler;

import java.io.InputStream;

public class TikaDocumentExtractor {
    public static String extractTextFromStream(InputStream stream) throws Exception {
        // -1 để không giới hạn độ dài ký tự trích xuất
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        AutoDetectParser parser = new AutoDetectParser();
        ParseContext context = new ParseContext();

        parser.parse(stream, handler, metadata, context);
        return handler.toString().trim();
    }
}
```

---

## 4.3. Module Lucene Core (Lập chỉ mục & Tìm kiếm)

### Nhiệm vụ chi tiết:

1. **Thiết kế Document Schema:**
   - `id` (`StringField`, Stored): Mã băm URL.
   - `url` (`StoredField`): Đường dẫn bài viết gốc.
   - `title` (`TextField`, Stored): Tiêu đề bài viết (phân tích từ, tìm kiếm và bôi đậm).
   - `content` (`TextField`, Stored): Nội dung bài viết hoặc text bóc tách từ file PDF/DOCX.
   - `subdomain` (`StringField`, Stored): Ví dụ `soict.hust.edu.vn` (phục vụ lọc theo viện/khoa).
   - `crawled_date` (`LongPoint`, Stored): Ngày cào bài viết (phục vụ sắp xếp theo thời gian).
2. **Vietnamese Analyzer Tùy Biến:**
   Kế thừa lớp `Analyzer` của Lucene, tích hợp:
   - `LowerCaseFilter`: Chuyển tất cả chữ về chữ thường.
   - `StopFilter`: Loại bỏ các từ dừng tiếng Việt (được định nghĩa trong `vietnamese_stopwords.txt`).
3. **Bộ xử lý truy vấn BooleanQuery (MUST & SHOULD):**
   - Cho phép kết hợp logic theo bài giảng lý thuyết:
     - Cụm từ bắt buộc xuất hiện gán cờ `BooleanClause.Occur.MUST`.
     - Cụm từ bổ trợ, tăng điểm xếp hạng gán cờ `BooleanClause.Occur.SHOULD`.
   - **Boosting đa trường:** Khớp từ khóa trong trường `title` sẽ được nhân điểm số cao hơn gấp đôi so với khớp trong `content` ($Boost_{title} = 2.0$, $Boost_{content} = 1.0$).

### Code mẫu Custom Vietnamese Analyzer (Java):

```java
package edu.hust.search.analyzer;

import org.apache.lucene.analysis.*;
import org.apache.lucene.analysis.standard.StandardTokenizer;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

public class VietnameseLuceneAnalyzer extends Analyzer {
    private final CharArraySet stopWords;

    public VietnameseLuceneAnalyzer() {
        this.stopWords = loadVietnameseStopWords();
    }

    private CharArraySet loadVietnameseStopWords() {
        Set<String> words = new HashSet<>();
        try (var is = getClass().getResourceAsStream("/vietnamese_stopwords.txt");
             var reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) words.add(line);
            }
        } catch (Exception e) {
            // Trường hợp không đọc được file thì dùng tập mặc định
            words.addAll(Set.of("và", "là", "của", "những", "các", "thì", "mà"));
        }
        return new CharArraySet(words, true);
    }

    @Override
    protected TokenStreamComponents createComponents(String fieldName) {
        Tokenizer tokenizer = new StandardTokenizer();
        TokenStream filter = new LowerCaseFilter(tokenizer);
        filter = new StopFilter(filter, stopWords);
        return new TokenStreamComponents(tokenizer, filter);
    }
}
```

### Code mẫu Tạo Chỉ mục & Tìm kiếm kết hợp BooleanQuery (Java):

```java
package edu.hust.search.query;

import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.nio.file.Paths;

public class LuceneSearchService {
    private final Directory directory;
    private final VietnameseLuceneAnalyzer analyzer;

    public LuceneSearchService(String indexPath) throws Exception {
        this.directory = FSDirectory.open(Paths.get(indexPath));
        this.analyzer = new VietnameseLuceneAnalyzer();
    }

    // Ví dụ xây dựng truy vấn BooleanQuery MUST và SHOULD
    public TopDocs searchAdvanced(String mustKeyword, String shouldKeyword, int topK) throws Exception {
        IndexReader reader = DirectoryReader.open(directory);
        IndexSearcher searcher = new IndexSearcher(reader);

        BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();

        // Điều kiện 1: Bắt buộc phải chứa mustKeyword trong Content hoặc Title
        Query mustQuery = new TermQuery(new Term("content", mustKeyword.toLowerCase()));
        booleanQuery.add(mustQuery, BooleanClause.Occur.MUST);

        // Điều kiện 2: Khuyến khích có thêm shouldKeyword (Nếu có sẽ cộng thêm điểm)
        // Boost điểm cho Title lên gấp đôi nếu khớp
        Query shouldQueryTitle = new BoostQuery(new TermQuery(new Term("title", shouldKeyword.toLowerCase())), 2.0f);
        Query shouldQueryContent = new TermQuery(new Term("content", shouldKeyword.toLowerCase()));

        booleanQuery.add(shouldQueryTitle, BooleanClause.Occur.SHOULD);
        booleanQuery.add(shouldQueryContent, BooleanClause.Occur.SHOULD);

        return searcher.search(booleanQuery.build(), topK);
    }
}
```

---

## 4.4. Module Evaluation (Đo đạc chỉ số MAP)

### Nhiệm vụ chi tiết:

1. **Thiết lập tập kiểm thử:**
   - Thu thập kho dữ liệu đạt quy mô tối thiểu **1000 bài viết/tài liệu**.
   - Xây dựng bộ gồm 20 - 30 câu truy vấn đại diện, mỗi câu dài khoảng 3 từ ($|Q| \sim 3$).
   - Ví dụ:
     - `Q01`: "học bổng khuyến khích"
     - `Q02`: "đồ án tốt nghiệp"
     - `Q03`: "thời khóa biểu k68"
     - `Q04`: "đăng ký tín chỉ"
     - `Q05`: "nghiên cứu khoa học"
2. **Gán nhãn Ground Truth (Tài liệu liên quan):**
   Tạo file `ground_truth_relevance.csv` lưu các cặp `(query_id, doc_id, is_relevant)`:
   ```csv
   query_id,doc_id,relevant
   Q01,doc_102,1
   Q01,doc_345,1
   Q01,doc_012,0
   Q02,doc_889,1
   ```
3. **Lập trình tính MAP tự động:** Chạy từng câu truy vấn qua hệ thống Lucene, lấy Top-50 kết quả trả về và tính toán $P@k$, $AP$, và cuối cùng là $MAP$.

### Code mẫu Thuật toán tính MAP (Java):

```java
package edu.hust.evaluation;

import java.util.*;

public class MapCalculator {

    /**
     * Tính Average Precision (AP) cho 1 câu truy vấn duy nhất
     * @param retrievedDocIds Danh sách mã tài liệu do Lucene trả về theo thứ tự xếp hạng
     * @param groundTruthRelevantDocIds Tập hợp các mã tài liệu thực tế có liên quan
     */
    public static double calculateAveragePrecision(List<String> retrievedDocIds, Set<String> groundTruthRelevantDocIds) {
        if (groundTruthRelevantDocIds.isEmpty()) return 0.0;

        double sumPrecision = 0.0;
        int relevantFoundCount = 0;

        for (int k = 0; k < retrievedDocIds.size(); k++) {
            String docId = retrievedDocIds.get(k);
            if (groundTruthRelevantDocIds.contains(docId)) {
                relevantFoundCount++;
                // Tính Precision tại vị trí k + 1
                double precisionAtK = (double) relevantFoundCount / (k + 1);
                sumPrecision += precisionAtK;
            }
        }

        return sumPrecision / groundTruthRelevantDocIds.size();
    }

    /**
     * Tính Mean Average Precision (MAP) trên toàn bộ tập câu truy vấn
     */
    public static double calculateMAP(Map<String, List<String>> allRetrievedResults,
                                     Map<String, Set<String>> allGroundTruths) {
        double totalAP = 0.0;
        int numQueries = allGroundTruths.size();

        for (String queryId : allGroundTruths.keySet()) {
            List<String> retrieved = allRetrievedResults.getOrDefault(queryId, Collections.emptyList());
            Set<String> groundTruth = allGroundTruths.get(queryId);

            double ap = calculateAveragePrecision(retrieved, groundTruth);
            totalAP += ap;
            System.out.printf("Query: %s -> AP: %.4f\n", queryId, ap);
        }

        return totalAP / numQueries;
    }
}
```

---

## 4.5. Module Web Interface & REST API

### Nhiệm vụ chi tiết:

- Cung cấp REST API tìm kiếm qua Spring Boot:
  - `GET /api/search?q={keyword}&subdomain={subdomain}&page=1`
  - Trả về danh sách kết quả gồm: Tiêu đề, Link bài viết, Điểm score, Snippet có bôi đậm từ khóa tìm kiếm (`<mark>từ_khóa</mark>`).
- Giao diện người dùng:
  - Ô nhập từ khóa tìm kiếm trực quan.
  - Hộp chọn lọc theo Viện / Trường con (`soict`, `sami`, `cfl`,...).
  - Phân trang và hiển thị số lượng bài viết tìm thấy cùng thời gian thực thi (ms).

---

# 5. MÔ HÌNH DỮ LIỆU & GIAO THỨC TRUYỀN TẢI (DATA SCHEMAS)

### 5.1. Cấu trúc bài viết chuẩn (Document Model)

Sau khi cào và bóc tách thành công, dữ liệu được chuẩn hóa dưới dạng JSON/POJO trước khi nạp vào Lucene:

```json
{
  "id": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  "url": "https://soict.hust.edu.vn/thong-bao-do-an-tot-nghiep-k64.html",
  "title": "Thông báo đăng ký đồ án tốt nghiệp kỳ 2023-2 cho sinh viên K64",
  "subdomain": "soict.hust.edu.vn",
  "content": "Viện Công nghệ Thông tin và Truyền thông thông báo về việc nộp đề cương và phân công giảng viên hướng dẫn...",
  "file_type": "HTML",
  "crawled_at": "2026-10-24T18:00:00Z",
  "content_hash": "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"
}
```

---

## 6. PHÂN CHIA CÔNG VIỆC CHI TIẾT CHO 5 THÀNH VIÊN

Bảng phân chia trách nhiệm được thiết kế độc lập, có đầu vào (Input) và đầu ra (Deliverable) rõ ràng:

| Thành viên                     | Vai trò phụ trách                                  | Nhiệm vụ kỹ thuật chi tiết                                                                                                                                                                                                                                   | Sản phẩm đầu ra cần bàn giao                                                                                                                                           |
| ------------------------------ | -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Thành viên 1 (Trưởng nhóm)** | **Crawler Lead & Hệ thống Quét Subdomain**         | - Lập trình crawler quét danh sách subdomain `*.hust.edu.vn`.<br>- Thu thập nội dung trang HTML và link tải file.<br>- Xây dựng cơ chế phát hiện bài mới qua mã băm SHA-256.<br>- Thiết lập scheduler chạy cào định kỳ.                                      | - Code module `crawler-module`.<br>- Cơ sở dữ liệu SQLite lưu danh sách URL và hash.<br>- Dataset thô đạt tối thiểu **1000 bài viết và file**.                         |
| **Thành viên 2**               | **Bóc tách Tika & Tiền xử lý Tiếng Việt**          | - Tích hợp thư viện Apache Tika để bóc tách văn bản từ `.pdf`, `.docx`.<br>- Viết hàm lọc bỏ HTML tag rác, menu, footer.<br>- Tích hợp thư viện tách từ ghép tiếng Việt.<br>- Xây dựng bộ từ điển từ dừng (Stopwords).                                       | - Code module `parser-module`.<br>- Pipeline chuyển đổi file thô sang `DocumentModel` sạch.<br>- Bộ test kiểm tra bóc tách file PDF/Word tiếng Việt không bị lỗi font. |
| **Thành viên 3**               | **Kiến trúc Chỉ mục Apache Lucene (Index)**        | - Thiết kế Document Schema và các kiểu Field (`StringField`, `TextField`, `LongPoint`).<br>- Viết `VietnameseLuceneAnalyzer` tùy biến.<br>- Cấu hình `IndexWriter` tối ưu hóa bộ đệm RAM.<br>- Viết quy trình nạp dữ liệu từ Parser vào Inverted Index.      | - Code module `LuceneIndexManager`.<br>- Thư mục Index hoàn chỉnh trên máy cục bộ chứa dữ liệu của 1000+ bài viết.                                                     |
| **Thành viên 4**               | **Lõi Tìm kiếm, BooleanQuery & Xếp hạng (Search)** | - Viết module `LuceneSearchService` xử lý tìm kiếm.<br>- Triển khai logic `BooleanQuery` kết hợp `MUST`, `SHOULD`.<br>- Tinh chỉnh điểm BM25 và trọng số Boosting (Title > Content).<br>- Tích hợp `Lucene Highlighter` để bôi đậm từ khóa trong trích đoạn. | - Module `query` trong `lucene-core-module`.<br>- Bộ Unit Test kiểm tra độ chính xác của các dạng truy vấn và xếp hạng.                                                |
| **Thành viên 5**               | **Thực nghiệm MAP & Giao diện Web**                | - Xây dựng tập test 25 câu truy vấn (\(Q \approx 3\)) và file gán nhãn ground truth.<br>- Viết module `MapCalculator` tự động đo đạc MAP.<br>- Dựng Web UI tìm kiếm bằng Spring Boot + HTML/JS.<br>- Xuất biểu đồ và bảng số liệu báo cáo MAP.               | - Web UI demo chạy mượt mà.<br>- Module `evaluation-module` tính MAP tự động.<br>- Báo cáo thực nghiệm chi tiết về điểm MAP.                                           |

# 7. KẾ HOẠCH TRIỂN KHAI TỪNG TUẦN (ROADMAP)

### Tuần 1: Khởi tạo dự án & Bóc tách thử nghiệm

- Cả nhóm: Họp thống nhất cấu trúc thư mục Maven đa module và Git convention.
- TV1: Viết script quét danh sách các subdomain hoạt động của HUST.
- TV2: Viết test case thử nghiệm dùng Tika đọc 5 file PDF thông báo của HUST.
- TV3 & TV4: Cài đặt thư viện Lucene 9.x/10.x, tạo index thử trên 10 văn bản mẫu.
- TV5: Lên khung wireframe cho giao diện Web tìm kiếm.

### Tuần 2: Gom dữ liệu quy mô lớn & Hoàn thiện Index Core

- TV1: Chạy crawler diện rộng trên các subdomain chính (SOICT, SAMI, Đào tạo...), gom đủ 1000 bài viết.
- TV2: Đưa toàn bộ 1000 bài viết qua bộ tiền xử lý và tách từ tiếng Việt.
- TV3: Hoàn thiện `VietnameseLuceneAnalyzer` và build thành công Inverted Index cho 1000 bài viết.
- TV4: Viết các phương thức tìm kiếm cơ bản và đo thời gian phản hồi.

### Tuần 3: Xử lý Truy vấn Nâng cao & Tinh chỉnh Xếp hạng

- TV4: Hoàn thành logic `BooleanQuery` (kết hợp MUST, SHOULD) và gán trọng số Boost cho trường Title.
- TV1: Bổ sung chức năng kiểm tra bài viết mới định kỳ qua SHA-256 hash.
- TV5: Ghép nối backend Spring Boot REST API với giao diện Web (gõ từ khóa, hiển thị danh sách có bôi đậm).

### Tuần 4: Đo đạc chỉ số MAP & Đánh giá Thực nghiệm

- TV5: Soạn thảo 25 câu truy vấn ($|Q| \sim 3$) và lập bảng gán nhãn Ground Truth.
- TV5 & TV4: Chạy module `MapCalculator` để tính MAP. Thử nghiệm so sánh điểm MAP giữa 2 trường hợp:
  - Trường hợp 1: Không có Boost (điểm mặc định).
  - Trường hợp 2: Có Boost trường Title và xử lý BooleanQuery SHOULD.
- Cả nhóm: Kiểm tra lỗi, tối ưu hiệu năng.

### Tuần 5: Đóng gói & Báo cáo

- Cả nhóm: Soạn tài liệu báo cáo kết quả bài tập lớn, chuẩn bị slide trình chiếu.
- Đóng gói mã nguồn dự án thành file `.zip` hoặc repository Git có file `README.md` hướng dẫn chạy chi tiết bằng 1 dòng lệnh Maven (`mvn spring-boot:run`).

---

# 8. TIÊU CHUẨN ĐÁNH GIÁ & CHECKLIST NGHIỆM THU

Để đảm bảo đạt điểm tối đa từ thầy cô, dự án cần thỏa mãn các tiêu chí sau:

- [ ] **Quy mô dữ liệu:** Đạt tối thiểu **1000 bài viết/tài liệu** được thu thập từ tên miền `hust.edu.vn` và các subdomain.
- [ ] **Bóc tách Tika:** Đọc được nội dung văn bản từ các tệp `.pdf` và `.docx` đính kèm bài viết.
- [ ] **Phát hiện nội dung mới:** Có demo cơ chế phát hiện khi một bài viết được chỉnh sửa nội dung hoặc thêm mới.
- [ ] **Lucene Core thuần:** Mã nguồn sử dụng trực tiếp các thư viện Java của Apache Lucene (`IndexWriter`, `BooleanQuery`, `IndexSearcher`).
- [ ] **Phân tích truy vấn:** Chứng minh được vai trò của `MUST` và `SHOULD` trong việc nâng cao chất lượng xếp hạng.
- [ ] **Đo đạc MAP:** Có số liệu thực nghiệm rõ ràng về chỉ số MAP trên tập câu hỏi độ dài khoảng 3 từ ($|Q| \sim 3$).
- [ ] **Sản phẩm hoàn chỉnh:** Có giao diện Web trực quan, tìm kiếm nhanh (< 100ms) và làm nổi bật (highlight) từ khóa được tìm.
