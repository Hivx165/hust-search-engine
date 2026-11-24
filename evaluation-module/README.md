# HUST Search Engine - Evaluation Module

Module đánh giá chất lượng xếp hạng của dự án bằng AP@50 và MAP@50. Module chạy benchmark trên chỉ mục Lucene thật, so sánh cấu hình `BASELINE` với `TITLE_BOOST_SHOULD`, rồi sinh báo cáo CSV, Markdown, HTML và SVG.

## Cấu trúc

```text
evaluation-module/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/edu/hust/evaluation/
│   │   │   ├── BenchmarkRunner.java
│   │   │   ├── MapCalculator.java
│   │   │   └── EvaluationReportExporter.java
│   │   └── resources/
│   │       ├── test_queries.json
│   │       └── ground_truth_relevance.csv
│   └── test/java/edu/hust/evaluation/
│       ├── BenchmarkRunnerTest.java
│       ├── MapCalculatorTest.java
│       └── EvaluationReportExporterTest.java
└── README.md
```

## Luồng benchmark

1. `BenchmarkRunner.loadQueries()` đọc [`test_queries.json`](src/main/resources/test_queries.json) thành danh sách `QueryDefinition`.
2. `BenchmarkRunner.loadGroundTruth()` đọc [`ground_truth_relevance.csv`](src/main/resources/ground_truth_relevance.csv) thành tập doc_id relevant theo từng query.
3. `BenchmarkRunner.runBoth()` mở `storage/lucene_index` ở chế độ read-only bằng `FSDirectory` và `DirectoryReader`.
4. `BenchmarkRunner.validateGroundTruthDocIds()` kiểm tra mọi doc_id relevant đều tồn tại trong field Lucene `id`.
5. `BenchmarkRunner.retrieveResults()` chạy mỗi truy vấn với hai mode:
   - `BASELINE`: chỉ truyền query làm từ khóa bắt buộc.
   - `TITLE_BOOST_SHOULD`: thêm cùng query dưới dạng SHOULD và boost `title=2.0`, `content=1.0`.
6. Mỗi truy vấn lấy tối đa 50 `ScoreDoc`, đọc stored field `id` và chuyển thành danh sách doc_id.
7. `MapCalculator.evaluateAtK()` tính AP@50 từng query và MAP@50 tổng hợp.
8. `EvaluationReportExporter` sinh các file báo cáo dưới thư mục `reports/`.

## Dữ liệu đánh giá hiện tại

- Cắt điểm Top-K: `50`.
- Số truy vấn: `25`.
- Số judgment ground truth: `119`.
- Relevant: `88`.
- Non-relevant: `31`.

CSV ground truth có schema:

```csv
query_id,doc_id,relevant
Q01,doc-id-thuc-te,1
Q01,doc-id-khac,0
```

Chỉ dòng có `relevant > 0` được đưa vào tập relevant của AP/MAP. Độ đo lấy mẫu trên 25 truy vấn và các judgment hiện tại, không đại diện cho việc đánh giá mọi tài liệu trong index.

## Chạy kiểm thử

Từ thư mục gốc repository:

```shell
mvn -pl evaluation-module test
```

Lệnh này chạy benchmark integration test với index Lucene giả lập, test công thức MAP/AP và test sinh báo cáo.

Chạy đầy đủ module cùng dependency:

```shell
mvn -pl evaluation-module -am test
```

## Chạy benchmark và sinh báo cáo

Yêu cầu `storage/lucene_index`, `test_queries.json` và `ground_truth_relevance.csv` phải tồn tại và hợp lệ.

Từ thư mục gốc:

```shell
mvn -pl evaluation-module -am verify
```

POM của evaluation module đăng ký `EvaluationReportExporter` tại Maven phase `verify`. Exporter chạy benchmark trên index thực và ghi vào thư mục `reports/` ở thư mục gốc:

```text
reports/
├── ap_at_50.csv
├── ap_at_50_comparison.svg
├── map_at_50_comparison.svg
├── benchmark_report.md
└── benchmark_dashboard.html
```

Có thể gọi exporter trực tiếp sau khi build module:

```shell
mvn -pl evaluation-module exec:java
```

Hoặc chỉ định thư mục xuất báo cáo:

```shell
java -cp <evaluation-runtime-classpath> edu.hust.evaluation.EvaluationReportExporter duong-dan-bao-cao
```

## Công thức đo

Với danh sách kết quả Top-K và tập relevant ground truth:

```text
P@rank = số tài liệu relevant đã tìm thấy / rank
AP@K   = tổng P@rank tại các tài liệu relevant / tổng tài liệu relevant trong ground truth
MAP@K  = trung bình cộng AP@K của các truy vấn có ít nhất một tài liệu relevant
```

`MapCalculator` loại doc_id trùng trong danh sách kết quả, bỏ rank sau khi vượt K, trả `NaN` cho truy vấn không có tài liệu relevant và bỏ query đó khỏi mẫu số MAP.

## Kết quả mới nhất

| Cấu hình | MAP@50 |
|---|---:|
| `BASELINE` | `0.8354` |
| `TITLE_BOOST_SHOULD` | `0.8166` |

Trên bộ nhãn hiện tại, TITLE BOOST làm MAP@50 thấp hơn BASELINE `0.0188`. Mở [`../../reports/benchmark_dashboard.html`](../../reports/benchmark_dashboard.html) để xem bảng AP và biểu đồ trực quan.
