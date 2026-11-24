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

Index phải đã được tạo/chỉnh sửa bởi `lucene-core-module` và có field `id`, `title`, `content`, `subdomain`, `url`.

Nếu thư mục index chưa tồn tại hoặc service không thể mở Searcher, ứng dụng Spring phải được dừng và kiểm tra thay vì coi lỗi thành tập kết quả rỗng. Với từ khóa rỗng, `SearchService` chủ động trả `PagedSearchResponse` với tổng hit bằng `0`.

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
- `page`: trang hiện tại; giá trị nhỏ hơn `1` được `SearchService` chuyển thành `1`.
- `size`: số kết quả mỗi trang; mặc định hoặc giới hạn là `10`, giá trị âm hoặc bằng `0` trở thành `10`, giá trị trên `100` trở thành `100`.

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

- `title`
- `url`
- `snippet`
- `score`

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
    -> LuceneSearchService
    -> QueryBuilderHelper
    -> storage/lucene_index
    -> PagedSearchResponse
    -> SearchResponse
    -> JSON trả về browser
```

`SearchApiController` tính `totalPages` từ `totalHits` và `size`. `LuceneSearchService` dùng cùng analyzer Lucene, MultiFieldQueryParser, Top-K phân trang và Highlighter.

Trong ứng dụng Web hiện tại, `SearchService` gọi `LuceneSearchService.doSearch(keyword, subdomain, page, size)` và không truyền SHOULD từ benchmark. Vì vậy luồng Web vẫn ở ranking baseline và không thay đổi cấu hình đánh giá của module evaluation.

## Xử lý lỗi

- Từ khóa rỗng: trả response tổng hit `0`, danh sách rỗng và không truy vấn Lucene.
- Lỗi parse/query hoặc lỗi Lucene: ném `IllegalStateException` thay vì nuốt thành danh sách rỗng.
- Tham số phân trang ngoài phạm vi: được chuẩn hóa trong `SearchService`.
- Lỗi mạng hoặc HTTP ở phía browser: `app.js` hiển thị thông báo lỗi kết nối.

## Kiểm thử

Chạy test module:

```shell
mvn -pl web-app-module test
```

Chạy đầy đủ test cùng dependency:

```shell
mvn -pl web-app-module -am test
```

Test hiện có trong [`src/test/java/edu/hust/web/service/SearchServiceTest.java`](src/test/java/edu/hust/web/service/SearchServiceTest.java) kiểm tra rằng lỗi parse query và lỗi Lucene không bị biến thành kết quả rỗng.
