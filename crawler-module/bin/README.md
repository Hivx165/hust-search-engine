# Phần việc thành viên 1: Crawler và phát hiện cập nhật

Yêu cầu: JDK 17+, Maven 3.9+. Từ thư mục gốc dự án:

```powershell
mvn -pl crawler-module -am clean verify
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --max-documents 1000 --max-visits 10000
```

Chạy định kỳ, nghỉ 5 phút sau mỗi lượt, kiểm tra lại URL đã thành công sau 60 phút; mỗi lượt xử lý tối đa 15 phút:

```powershell
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --max-documents 1000 --max-visits 10000 --interval-minutes 5 --revisit-minutes 60 --round-minutes 15
```

Dừng bằng Ctrl+C. Có thể truyền `--seeds path/to/seeds.txt`, `--storage storage`, `--delay-ms 1000`. Mỗi dòng seed là URL hoặc hostname HUST; dòng bắt đầu bằng `#` là chú thích. Mặc định đọc danh sách trong JAR.

## Chạy nền và tự cập nhật trên Windows

Từ thư mục gốc, sau khi build JAR:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action enable-autostart
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action status
```

Lệnh đầu đăng ký một Scheduled Task cho người dùng hiện tại, khởi động khi đăng nhập Windows và tự khởi động lại sau lỗi tiến trình. Task chạy ở quyền người dùng, cửa sổ ẩn, đọc resource mới mỗi lượt, ghi log trong `storage/logs/`. Script tìm Java 17+; trên máy hiện tại có thể dùng JDK của Android Studio. Có thể truyền `-JavaPath`, `-StoragePath`, `-IntervalMinutes`, `-RevisitMinutes`, `-RoundMinutes`, `-MaxDocuments`, `-MaxVisits`, `-DelayMs`.

```powershell
# Chỉ chạy nền ngay, không đăng ký tự khởi động:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action start
# Dừng lượt chạy hiện tại:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action stop
# Gỡ lịch tự khởi động:
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action disable-autostart
```

Máy phải bật, có mạng và đã đăng nhập để công việc tiếp tục; nếu cần chạy cả khi máy này tắt thì triển khai cùng JAR lên máy chủ chạy liên tục. Khóa file ngăn hai tiến trình sử dụng cùng kho dữ liệu. Sau khi dừng đột ngột, URL đang xử lý được thử lại sau khi lease 5 phút hết hạn; URL khác vẫn đi tiếp bình thường.

## Mở rộng phạm vi và giữ nội dung mới

- Hàng đợi SQLite `frontier.db` giữ toàn bộ URL đã tìm thấy, lịch duyệt lại và thời gian thử lại. Hết giới hạn một lượt sẽ tiếp tục phần chưa xong ở lượt sau, không quay lại từ đầu và bỏ mất hàng đợi.
- URL chưa tải và các origin ít được ghé được ưu tiên; mỗi 5 lượt lấy URL có một lượt dành cho URL cũ đã đến lịch kiểm tra, để việc tìm bài mới không làm ngừng cập nhật bài cũ.
- Crawler tự đọc Sitemap trong robots.txt, thử các địa chỉ sitemap phổ biến, đi tiếp qua sitemap index, sitemap gzip và RSS/Atom; các nguồn này bổ sung những bài không nằm trong liên kết trang đầu.
- Gửi `If-None-Match`/`If-Modified-Since` khi có ETag/Last-Modified. HTTP 304 dùng lại nội dung đã lưu, vẫn cập nhật thời điểm kiểm tra và khám phá liên kết; HTTP 200 được kiểm tra SHA-256 như trước.
- Mỗi nguồn CT có cache và lịch kiểm tra riêng, mặc định làm mới sau 6 giờ. DNS dương lưu 6 giờ, DNS chưa phân giải được thử lại sau 30 phút. `Retry-After` của nguồn CT và origin web được giữ qua khởi động lại và kiểm tra cả khi redirect.
- Loại tham số theo dõi như UTM/fbclid để tránh tải lặp cùng bài. Lỗi kết nối/TLS của HTTPS cho phép thử URL HTTP cùng hostname, giữ kiểm tra robots và giới hạn tần suất riêng; không bỏ kiểm tra chứng chỉ TLS.

Trạng thái hiện tại nằm trong `storage/lifecycle.properties`, `storage/crawler_status.properties` và `storage/last_run.txt`. Đây là mở rộng bằng code; số miền tìm được vẫn phụ thuộc dữ liệu công khai và khả năng truy cập, không bảo đảm tìm được toàn bộ miền nội bộ hoặc miền không có dấu vết công khai.

## Tự khám phá và lưu subdomain

Đã bổ sung các nguồn trong bản thiết kế người dùng gửi: Wayback CDX, Common Crawl, wordlist DNS có kiểm tra wildcard, import file ngoài và CSV kiểm tra HTTP. Xem [đối chiếu chi tiết với thiết kế tham khảo](REFERENCE_ALIGNMENT.md) để biết phần đã có, phần mới bổ sung và phần chưa triển khai.

Mặc định các nguồn archive/wordlist và kiểm tra HTTP được bật; có thể dùng `--archives false`, `--wordlist-enabled false`, `--live-check false`. File tùy chọn: `--wordlist path/to/words.txt`, `--external-subdomains data/external-subdomains.txt`. Các truy vấn archive có phân trang, cache, Retry-After và giới hạn mỗi lượt; kiểm tra HTTP tối đa 24 hostname mới/lượt để không chặn lâu việc crawl nội dung.

Danh sách tổng hợp nằm tại `storage/discovery/subdomains.txt`; thống kê từng nguồn ở `storage/discovery/source_counts.csv`; bảng kiểm tra HTTP tại `storage/discovery/live-hosts.csv`. Nguồn archive chỉ chứng minh URL từng xuất hiện, chưa chứng minh website hiện đang truy cập được. Wordlist là giả thuyết, chỉ kết quả có DNS và không trùng mẫu DNS wildcard mới được công nhận từ nguồn đó.

Mặc định `--discovery-mode hybrid`: chương trình tự truy vấn Certificate Transparency (crt.sh và Cert Spotter), loại trùng tên miền cụ thể, kiểm tra DNS rồi đưa các miền phân giải được vào hàng đợi crawler. Sau đó crawler tiếp tục khám phá qua trang web. Để chỉ khám phá qua trang web/sitemap/feed dùng `--discovery-mode links`; để kiểm kê CT/DNS mà chưa tải bài viết dùng:

```powershell
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --discovery-mode passive --subdomains-output crawler-module/src/main/resources/initial_subdomains.txt
```

Chỉ duyệt liên kết không đủ để tìm các miền không được trỏ tới từ những trang đã tải. CT bổ sung một nguồn độc lập; chương trình tự truy vấn nguồn này, không dùng danh sách tên miền chép tay. [Tài liệu Cert Spotter](https://sslmate.com/help/reference/ct_search_api_v1) mô tả `include_subdomains`, `expand=dns_names` và phân trang bằng `after`; API này liệt kê chứng chỉ còn hạn và có giới hạn truy vấn khi không dùng tài khoản. crt.sh có thể chứa dữ liệu lịch sử. Nguồn lỗi/429 hoặc hết giới hạn phân trang được báo là chưa đầy đủ, vẫn giữ ứng viên đã thu thập/cache.

Kết quả của bước thụ động nằm trong `storage/passive-discovery/`:

- `candidates.txt`: hostname cụ thể từ nguồn, đã loại trùng, không tính miền gốc.
- `wildcards.txt`: mẫu wildcard như `*.faculty.hust.edu.vn`, không tính là hostname cụ thể và không tự tạo các miền con từ mẫu này.
- `dns_status.csv`: trạng thái `RESOLVED`/`UNRESOLVED`, địa chỉ IP và thời điểm kiểm tra.
- Các phản hồi gốc, trạng thái nguồn và `summary.txt`: bằng chứng để đối chiếu số lượng và lỗi nguồn.

Hostname trong chứng chỉ không chứng minh DNS hiện tại còn hoạt động; phân giải DNS cũng không chứng minh tải được website. Vì vậy báo cáo phân biệt số ứng viên CT, số có DNS và số subdomain thực sự lưu được nội dung trong SQLite. Không tự tăng số lượng bằng tên đoán hoặc đếm trùng chứng chỉ, URL, HTTP/HTTPS. Mốc “900” cần đối chiếu cùng loại dữ liệu và nguồn đo.

Lượt chạy thật ngày 05/11/2026: 4.630 bản ghi crt.sh, tổng hợp hai nguồn được 245 hostname cụ thể duy nhất; 159 hostname có A/AAAA trong lượt kiểm tra, 86 chưa phân giải được, hai mẫu wildcard được lưu riêng. Resource tăng từ 65 lên 189 subdomain sau khi bổ sung các miền có DNS. Phản hồi nguồn và báo cáo nằm tại `storage/passive-run-20261105/` trên máy chạy; dữ liệu này không đưa vào Git. Bộ kiểm thử đã mở rộng để kiểm tra archive, wordlist và HTTP probe; xem kết quả Maven hiện tại trong `target/surefire-reports/`.

`initial_subdomains.txt` là điểm xuất phát. Trong lúc chạy, crawler tự lấy URL từ liên kết, iframe/frame, form, các thuộc tính URL và URL HTTP(S) trong script/JSON của trang đã tải. Nó ưu tiên ghé origin mới trước khi tiếp tục hàng loạt bài viết trong một origin, sau đó khám phá tiếp từ trang của miền mới. Không cần bổ sung tên miền bằng tay để đi tiếp qua chuỗi liên kết.

Kết quả được gộp với các lượt trước và ghi ngay mỗi khi phát hiện origin mới, không đợi kết thúc lượt. Crawler tự đọc lại `storage/discovered_seeds.txt` khi khởi động/lập lịch; file giữ scheme và URL đầu vào, ưu tiên URL đã tải thành công, kể cả website chỉ dùng HTTP hoặc trang vào nằm ở đường dẫn con. `discovered_subdomains.txt` chỉ chứa hostname duy nhất và không tính miền gốc `hust.edu.vn`.

Để crawler **tự bổ sung vào resource** đang mở trong IDE, chạy:

```powershell
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --subdomains-output crawler-module/src/main/resources/initial_subdomains.txt --max-documents 1000 --max-visits 10000
```

Tùy chọn này giữ các dòng seed và chú thích sẵn có, chỉ thêm URL còn thiếu. Nếu không truyền `--subdomains-output`, crawler vẫn tự lưu và dùng lại kết quả trong `storage/`; resource đóng gói bên trong JAR không bị sửa. `discovered_seeds.txt` giữ URL đầu vào của mỗi origin; `frontier.db` giữ hàng đợi đầy đủ. Khám phá qua website không bảo đảm tìm được subdomain không có liên kết công khai hoặc chỉ xuất hiện khi thực thi JavaScript.

## Dữ liệu bàn giao cho thành viên 2

- `storage/raw_html/<sha256-url>.html`: HTML gốc, giữ nguyên byte và charset để parser xử lý.
- `storage/raw_files/<sha256-url>.<pdf|docx|doc|xlsx>`: file đính kèm.
- `storage/raw_feeds/<sha256-url>.xml`: XML sitemap/RSS/Atom để khám phá, không tính là bài viết trong `Report.saved`.
- `storage/crawler.db`: SQLite, bảng `crawled_pages`, mỗi URL có một bản ghi mới nhất.
- `storage/manifest.csv`: danh sách tài liệu UTF-8 để bàn giao mà không cần truy vấn SQLite; xuất khi lượt chạy kết thúc, `id` là SHA-256 URL, `crawled_at` lấy từ `last_scraped_at`.
- `storage/crawl_urls.csv`: mỗi URL HTML/tài liệu một dòng, có tiêu đề và loại trang; loại XML khám phá khỏi bảng này.
- `storage/articles.csv`: các trang được nhận diện là bài viết từ HTML đã tải. Đây là phân loại bằng quy tắc, cần kiểm tra trước khi dùng làm nhãn đánh giá.
- `storage/links.csv`: bảng liên kết `source_url` → `target_url`, kèm hostname, anchor text và loại liên kết. Một trang nguồn có nhiều đích sẽ xuất hiện nhiều dòng; mỗi bộ nguồn/đích/loại chỉ xuất một lần. URL đích được nhìn thấy trong HTML không đồng nghĩa đã tải thành công.
- `storage/page_quality.csv`: kết quả phân loại HTML và lý do, giúp xem các trang danh mục, đăng nhập, trang mặc định, thiếu nội dung và trang nội dung khác.
- `storage/dataset.db`: cache phân tích HTML và các liên kết, tách biệt cơ sở dữ liệu crawl; chỉ phân tích lại khi nội dung hoặc bộ phân loại thay đổi.
- `storage/dataset_export_status.properties`: thời điểm và trạng thái xuất gần nhất; kiểm tra file này nếu CSV chưa cập nhật được.
- `storage/discovered_subdomains.txt`: hostname đã phát hiện, được gộp qua các lượt chạy và lưu ngay trong lúc chạy; không khẳng định tất cả đều truy cập được.
- `storage/discovered_seeds.txt`: URL đầu vào của mỗi origin để crawler tự dùng lại ở lượt sau; phân biệt HTTP/HTTPS, giữ đường dẫn con, ưu tiên URL đã tải thành công.
- `storage/last_run.txt`: thống kê lượt chạy và tổng số URL lưu thành công.
- `storage/frontier.db`: hàng đợi và lịch crawl/retry/lease bền vững.

Bảng SQLite gồm `url`, `content_hash` (SHA-256 byte nội dung), `last_scraped_at` (UTC ISO-8601), `status` (`NEW`, `UPDATED`, `UNCHANGED` so với lần tải thành công trước), `local_path`, `content_type`, `title`, `subdomain`. Parser đọc đường dẫn từ `local_path`; dữ liệu chưa được làm sạch hay tách từ. Ví dụ thống kê:

```sql
SELECT content_type, count(*) FROM crawled_pages GROUP BY content_type;
SELECT url, local_path FROM crawled_pages;
```

Các bảng CSV mới tự cập nhật cuối mỗi lượt crawl, có UTF-8 BOM để mở tiếng Việt trong Excel. Bộ phân loại chỉ hỗ trợ xem và lọc dữ liệu; HTML gốc vẫn bàn giao cho Parser để trích xuất đầy đủ nội dung. Không gộp HTTP với HTTPS hoặc hai đường dẫn khác nhau chỉ vì trông giống nhau. `links.csv` giữ quan hệ liên kết; `crawl_urls.csv` giữ các trang đã lưu. Số dòng hai bảng không phải số subdomain. Mỗi file được thay thế riêng; nếu một file bị khóa hoặc xuất lỗi, trạng thái xuất ghi `error` và một số bảng có thể còn thuộc lượt trước. Chỉ đối chiếu cả bộ sau khi trạng thái ghi `success`.

Để xuất lại từ dữ liệu đã có mà không truy cập mạng, dừng tiến trình đang dùng cùng storage rồi chạy:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action stop
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --storage storage --export-only true
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/crawler.ps1 -Action start
```

Lần đầu cần phân tích các HTML đã lưu; các lần sau dùng cache cho nội dung không đổi. Không chạy song song hai crawler/export cùng storage.

`max-documents` giới hạn số tài liệu lưu trong **mỗi lượt**, bao gồm trang giữ nguyên; XML feed không tính vào giới hạn này. `max-visits` giới hạn số URL được lấy ra xử lý; `round-minutes` là giới hạn mềm của phần crawl, có thể vượt thêm thời gian request/redirect và xử lý đang dở. Phần CT/DNS có timeout và cache riêng. Chạy lại tiếp tục hàng đợi và lịch đã lưu, không tăng số bản ghi cho URL đã có. Bảng `crawled_pages`/manifest có cả XML khám phá; khi đếm dữ liệu bàn giao cho Parser cần lọc `content_type` để chỉ lấy HTML và tài liệu.

Crawler dùng hàng đợi bền vững, cân bằng các origin và lịch duyệt lại. Chỉ chấp nhận HTTP(S) trên `hust.edu.vn` và các subdomain, kiểm tra miền cùng cooldown trước từng request và redirect, kể cả robots redirect. Tôn trọng Allow/Disallow và Crawl-delay trong robots.txt (nhóm riêng hoặc `*`, wildcard, quy tắc dài nhất), mặc định nghỉ 1 giây giữa các yêu cầu cùng hostname và tăng theo Crawl-delay nếu cần. robots.txt trả 404/410 được coi là không có quy tắc; lỗi khiến origin được bỏ qua hoặc thử lại sau, redirect của robots được theo tối đa 5 lần trong phạm vi HUST. HTTP 429/503 giữ cooldown tối thiểu 15 phút hoặc lâu hơn theo Retry-After cho cả HTTP và HTTPS trong SQLite; ba phản hồi 403/5xx liên tiếp có thể dừng hostname 6 giờ. Không thực thi JavaScript, không đăng nhập. Mỗi phản hồi và dữ liệu gzip giải nén giới hạn 20 MiB. Lỗi tải được ghi ra stderr, tiếp tục URL khác; hash chỉ ghi sau khi lưu file thành công.

## Kiểm chứng và giới hạn nghiệm thu

Kiểm thử tự động dùng HTTP proxy cục bộ, không truy cập website thật: kiểm tra HTML/PDF, chặn URL ngoài HUST và robots, crawl lại phát hiện thay đổi, SQLite tồn tại sau khi mở lại, chuẩn hóa URL và hash.

Mốc 1000 tài liệu phải kiểm tra bằng số bản ghi thực trong SQLite sau khi thu thập. HTML có thể gồm trang danh mục/trang chủ, nên số URL không tự chứng minh đủ 1000 **bài viết**. Cần thành viên 2 lọc nội dung và đếm lại, tăng giới hạn nếu thiếu. Dataset không đưa vào Git; không tạo dữ liệu giả để đạt chỉ tiêu.

Root POM hiện chỉ kích hoạt `crawler-module` vì các module thành viên khác còn là khung rỗng; thêm chúng khi có POM và triển khai tương ứng.

Trên máy hiện tại có thể dùng JDK đi kèm Android Studio nếu `java` mặc định đang là Java 8:

```powershell
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
& .\.tools\apache-maven-3.9.9\bin\mvn.cmd -pl crawler-module -am verify
& "$env:JAVA_HOME\bin\java.exe" -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --max-documents 1500 --max-visits 15000
```

`.tools` là công cụ tải cục bộ, không nằm trong Git. Máy khác dùng Maven đã cài và JDK 17+ như hướng dẫn đầu tài liệu.
