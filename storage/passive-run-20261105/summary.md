# Kết quả khám phá subdomain tự động ngày 05/11/2026

Chạy chính JAR của crawler ở chế độ `--discovery-mode passive`, không chép danh sách hostname thủ công. Chế độ này kiểm kê nguồn CT và DNS, chưa tải nội dung HTTP từ các hostname mới.

| Chỉ số | Kết quả |
| --- | ---: |
| Bản ghi chứng chỉ crt.sh trả về | 4630 |
| Hostname HUST cụ thể, duy nhất từ các nguồn CT | 245 |
| Hostname phân giải A/AAAA được trong lượt kiểm tra | 159 |
| Hostname chưa phân giải được trong lượt kiểm tra | 86 |
| Mẫu wildcard, lưu riêng và không cộng vào hostname | 2 |
| Subdomain trong resource trước lượt chạy | 65 |
| Subdomain trong resource sau lượt chạy | 189 |
| Subdomain mới được tự bổ sung vào resource | 124 |
| Hostname phân giải được nhưng thiếu trong resource | 0 |

crt.sh trả HTTP 200; Cert Spotter trả hai trang có dữ liệu và một trang rỗng kết thúc phân trang. Hai truy vấn kết thúc bình thường. Điều này không khẳng định đã tìm mọi subdomain của HUST.

Hostname trong nguồn CT, hostname có DNS và website có nội dung tải được là ba chỉ số khác nhau. Một chứng chỉ có thể xuất hiện nhiều lần hoặc chứa nhiều SAN; chương trình loại trùng hostname, không dùng số bản ghi chứng chỉ làm số subdomain. `UNRESOLVED` chỉ mô tả lần kiểm tra DNS này.

Bằng chứng:

- `passive-discovery/candidates.txt`: toàn bộ 245 hostname cụ thể.
- `passive-discovery/dns_status.csv`: kết quả A/AAAA và thời điểm kiểm tra.
- `passive-discovery/wildcards.txt`: hai mẫu wildcard.
- `passive-discovery/crtsh-page-001.response`: phản hồi crt.sh gốc.
- `passive-discovery/certspotter-page-*.response`: các trang Cert Spotter gốc.
- `passive-discovery/source-status.txt`: URL truy vấn, mã HTTP và trạng thái hoàn tất.

Lệnh tái lập (kết quả phụ thuộc dữ liệu nguồn và DNS tại thời điểm chạy):

```powershell
java -jar crawler-module/target/crawler-module-1.0-SNAPSHOT.jar --discovery-mode passive --storage storage/passive-run-20261105 --subdomains-output crawler-module/src/main/resources/initial_subdomains.txt
```
