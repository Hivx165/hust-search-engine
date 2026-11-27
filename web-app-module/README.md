# HUST Search Engine - Web Application Module

Module giao diện và REST API của HUST Search Engine. Module dùng Spring Boot 3.2.0, truy vấn Lucene qua `LuceneSearchService`, đọc danh mục subdomain và trả dữ liệu phân trang cho giao diện HTML/JavaScript.

## Cấu trúc

```text
web-app-module/
├── pom.xml
├── src/
│   ├── main/
│   │   ├── java/edu/hust/web/
│   │   │   ├── SearchEngineApplication.java
│   │   │   ├── config/LuceneConfig.java
│   │   │   ├── controller/
│   │   │   ├── dto/
│   │   │   ├── service/SearchService.java
│   │   │   └── util/SubdomainUtil.java
│   │   └── resources/
│   │       ├── application.yml
│   │       ├── data/subdomains.json
│   │       └── static/
│   │           ├── index.html
│   │           ├── app.js
│   │           └── style.css
│   └── test/java/edu/hust/web/service/
│       └── SearchServiceTest.java
└── README.md
```

## Yêu cầu dữ liệu

Ứng dụng mặc định mở Lucene index tại:

```text
storage/lucene_index
```

Index phải được tạo bởi `lucene-core-module` và có các field `id`, `title`, `content`, `subdomain`, `url`.

Index được mở khi Spring khởi tạo `LuceneSearchService`; nếu không mở được, ứng dụng không khởi động thành công. Với từ khóa rỗng, `SearchService` trả `SearchResponseDTO` có tổng hit bằng `0` và danh sách kết quả rỗng.

## Chạy ứng dụng

Từ thư mục gốc repository:

```shell
mvn -pl web-app-module -am spring-boot:run
```

Hoặc chạy module riêng sau khi các dependency module đã được cài:

```shell
mvn -pl web-app-module spring-boot:run
```

Mặc định ứng dụng chạy tại:

```text
http://localhost:8088
```

Cấu hình port nằm trong [`src/main/resources/application.yml`](src/main/resources/application.yml).

## Giao diện Web

Mở trình duyệt:

```text
http://localhost:8088/
```

Giao diện static tại [`src/main/resources/static/index.html`](src/main/resources/static/index.html) hỗ trợ:

- Tìm kiếm theo từ khóa.
- Lọc theo subdomain.
- Phân trang.
- Lưu tối đa năm từ khóa gần nhất trong `localStorage`.
- Đồng bộ `keyword`, `subdomain`, `page` trên URL.
- Hiển thị snippet HTML được highlighter bôi đậm bằng thẻ `<mark>`.
- Hiển thị kết nối/không có kết quả.

## REST API

### Tìm kiếm

```http
GET /api/search?keyword={keyword}&subdomain={subdomain}&page=1&size=10
```

Ví dụ:

```shell
curl "http://localhost:8088/api/search?keyword=tuyển%20sinh&subdomain=&page=1&size=10"
```

Tham số:

- `keyword`: từ khóa bắt buộc về mặt logic giao diện; mặc định chuỗi rỗng.
- `subdomain`: tên miền con; chuỗi rỗng hoặc bỏ qua để tìm toàn bộ.
- `page`: trang hiện tại, bắt đầu từ `1`; mặc định là `1`.
- `size`: số kết quả mỗi trang; mặc định là `10`. Giá trị hợp lệ là từ `1` đến `100`.

`SearchService` chuẩn hóa `page` và `size` trước khi truy vấn Lucene. Tuy nhiên, response hiện dùng `page` đầu vào làm `currentPage` và controller tính `totalPages` từ `size` đầu vào. Vì vậy, hãy gửi `page >= 1` và `1 <= size <= 100` để metadata phân trang chính xác.

Phản hồi JSON do `SearchResponse` tạo:

```json
{
  "keyword": "tuyển sinh",
  "totalResults": 100,
  "currentPage": 1,
  "totalPages": 10,
  "processingTimeMs": 12,
  "results": []
}
```

Mỗi phần tử kết quả có:

- `id`
- `title`
- `url`
- `snippet`
- `score`
- `subdomain`
- `crawledDate`
- `fileType`

### Danh mục subdomain

```http
GET /api/subdomains
```

Danh mục được [`SubdomainUtil`](src/main/java/edu/hust/web/util/SubdomainUtil.java) đọc từ `src/main/resources/data/subdomains.json`.

## Luồng xử lý backend

```text
Browser / app.js
    -> SearchApiController
    -> SearchService
    -> LuceneSearchService.searchPaged(...)
    -> QueryBuilderHelper
    -> storage/lucene_index
    -> SearchResponseDTO
    -> SearchApiController chuyển thành SearchResponse
    -> JSON trả về browser
```

`LuceneSearchService.searchPaged(...)` dựng query có bộ lọc subdomain, lấy tổng số hit và cắt kết quả theo trang. Query dùng `VietnameseLuceneAnalyzer`, BooleanQuery với title boost và BM25; kết quả có snippet được tạo bằng Lucene Highlighter.

Luồng Web dùng `searchPaged(...)` của Lucene core, không dùng cấu hình SHOULD của benchmark trong `evaluation-module`.

## Xử lý lỗi

- Từ khóa rỗng: trả response tổng hit `0`, danh sách rỗng và không truy vấn Lucene.
- Lỗi query hoặc lỗi Lucene trong lúc tìm kiếm: `SearchService` hiện bắt exception và trả response rỗng với tổng hit `0`. Response này không phân biệt được với trường hợp không có kết quả; cần xem log/backend nếu gặp bất thường.
- `SearchService` chuẩn hóa tham số phân trang trước khi gọi Lucene; để `currentPage` và `totalPages` nhất quán, chỉ gửi `page >= 1` và `size` trong khoảng `1–100`.
- Lỗi mạng hoặc response JSON không hợp lệ ở phía browser: `app.js` hiển thị thông báo lỗi kết nối.

## Kiểm thử

Chạy test module:

```shell
mvn -pl web-app-module test
```

Chạy đầy đủ test cùng dependency:

```shell
mvn -pl web-app-module -am test
```

Test hiện có trong [`src/test/java/edu/hust/web/service/SearchServiceTest.java`](src/test/java/edu/hust/web/service/SearchServiceTest.java) kiểm tra response rỗng khi query không hợp lệ hoặc Lucene gặp lỗi.
