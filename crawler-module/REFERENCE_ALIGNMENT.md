# Đối chiếu với cách triển khai người dùng gửi ngày 07/11/2026

Đã tham khảo và bổ sung các phần khám phá còn thiếu vào module của thành viên 1. Đây chưa phải tuyên bố hoàn thành toàn bộ thiết kế gồm Discovery, Crawler, Indexer và Web trong tài liệu.

| Hạng mục | Trạng thái hiện tại |
| --- | --- |
| crt.sh, chuẩn hóa, loại trùng, kiểm tra DNS | Đã có, thêm Cert Spotter và cache/cooldown theo nguồn. Wildcard được lưu riêng. |
| Wayback CDX | Đã bổ sung, lấy hostname từ URL lịch sử và tiếp tục bằng resume key. |
| Common Crawl | Đã bổ sung, đọc danh mục index hiện tại từ API và tiếp tục phân trang qua các lượt. |
| Wordlist DNS | Đã bổ sung 122 giả thuyết mặc định, có file tùy chọn, giới hạn concurrency và kiểm tra wildcard DNS. Chỉ kết quả có DNS mới được công nhận từ nguồn wordlist. |
| Import subfinder/amass | Đã bổ sung đọc file tùy chọn, nhận hostname/URL/CSV. Không tự thực thi công cụ bên ngoài. |
| CSV kiểm tra HTTP | Đã bổ sung HEAD/GET có kiểm tra robots, redirect, cooldown và cache; tối đa 24 hostname mới/lượt, ưu tiên hostname chưa kiểm tra. |
| Tìm miền qua trang web, sitemap, RSS/Atom | Đã có, kết quả mới được gộp ngay vào danh sách tổng hợp. |
| Lưu frontier, tiếp tục sau khi dừng, ETag/Last-Modified | Đã có; chạy định kỳ và tự khởi động trên Windows. |
| Bảng URL từng trang và liên kết nguồn → đích | Đã có `crawl_urls.csv`, `links.csv`, `articles.csv` và `page_quality.csv`, xuất từ dữ liệu thật đã tải và tự cập nhật cuối lượt. Phân loại bài viết dùng quy tắc, chưa thay thế Parser. |
| Robots, Crawl-delay, chờ giữa request | Đã có; Crawl-delay và delay áp dụng theo hostname, gồm cả HTTP/HTTPS. |
| Circuit breaker | 429/503 giữ Retry-After; ba phản hồi 403/5xx liên tiếp có thể dừng hostname 6 giờ, lưu cooldown cho HTTP và HTTPS. |
| 8–16 worker cho crawler web | Chưa có. Web crawler hiện một worker; DNS tối đa 8, kiểm tra HTTP tối đa 4. |
| Depth/page cap riêng từng host, trap detection, canonical URL | Chưa hoàn chỉnh. Hiện có giới hạn URL/tài liệu/thời gian mỗi lượt, loại trùng và bỏ tracking; không tự sắp xếp mọi query hoặc thay URL theo canonical. |
| User-Agent/email từ application.yml, SLF4J | Chưa theo mẫu. Hiện cấu hình bằng CLI/script, User-Agent cố định và log stdout/stderr. |
| Java 21, dnsjava, Crawler4j, module discovery riêng | Chưa chuyển stack. Giữ Java target 17 (chạy trên Java 21+), HttpClient/Jsoup/JNDI và cấu trúc module của nhóm. |
| Main text/meta/language, Tika, analyzer, Lucene, API/UI | Thuộc phần Parser/Index/Search/Web của các thành viên khác; chưa triển khai trong lượt này. |

## Chạy phần khám phá mở rộng

```powershell
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --discovery-mode passive --storage storage --subdomains-output crawler-module/src/main/resources/initial_subdomains.txt
```

Các nguồn mới mặc định bật khi dùng `hybrid` hoặc `passive`. Có thể tắt riêng hoặc truyền file:

```text
--archives false
--wordlist-enabled false
--wordlist path/to/words.txt
--external-subdomains data/external-subdomains.txt
--live-check false
```

File external mặc định được dùng nếu tồn tại; thiếu file không phải lỗi. Khi tắt wordlist vẫn có thể nhập external. Chỉ hostname thuộc đúng `hust.edu.vn` hoặc hậu tố `.hust.edu.vn` được chấp nhận; miền gốc không tính vào số subdomain.

## Kết quả và giới hạn cần đọc đúng

- `storage/discovery/subdomains.txt`: danh sách hostname tổng hợp từ các nguồn và crawler, không phải danh sách mọi website đang hoạt động.
- `storage/discovery/source_counts.csv`: số hostname theo nguồn; các nguồn có thể trùng nhau, không cộng các dòng để tính tổng duy nhất.
- `storage/passive-discovery/dns_status.csv`: DNS và nguồn của từng hostname.
- `storage/discovery/live-hosts.csv`: `host,ip,http_status,final_url,title,status,checked_at`; HEAD thường không cung cấp title. `NOT_CHECKED_YET`, `DNS_UNRESOLVED`, `ROBOTS_DENIED` và lỗi mạng không được báo thành website hoạt động.
- `storage/wordlist-discovery/generated_candidates.txt`: các tên đã thử/chuẩn bị thử, không phải hostname đã phát hiện. Kết quả trùng DNS wildcard được lưu riêng, không nâng số lượng phát hiện.
- Lịch sử phản hồi, cursor và lỗi archive nằm trong `storage/passive-discovery/`; mỗi nguồn tối đa ba trang/lượt, lưu vị trí để tiếp tục, giữ cache khi lỗi/429. Kết thúc một phần truy vấn không đồng nghĩa đã tìm hết HUST.

Thiết kế tham khảo đề xuất bỏ `*.`; bản này giữ mẫu wildcard làm bằng chứng riêng, không biến mẫu thành hostname có thật. Wayback CDX không có cột `host` trong các trường chuẩn, nên truy vấn collapse theo URL rồi loại trùng hostname trong Java; không đếm URL/chứng chỉ thành subdomain.

Tài liệu API dùng để triển khai: [Wayback CDX](https://github.com/internetarchive/wayback/blob/master/wayback-cdx-server/README.md), [Common Crawl Index](https://index.commoncrawl.org/).
