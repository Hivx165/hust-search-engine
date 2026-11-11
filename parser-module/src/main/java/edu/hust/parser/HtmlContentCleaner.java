
package edu.hust.parser;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

public class HtmlContentCleaner {

    public static String cleanHtml(String html) {

        if (html == null || html.isBlank()) {
            return "";
        }

        // Chuyển HTML thành Document
        Document doc = Jsoup.parse(html);

        // Loại bỏ các thành phần không cần thiết
        doc.select(
            "script, style, nav, header, footer, " +
            "aside, iframe, noscript"
        ).remove();

        // Lấy văn bản thuần
        return doc.body().text().trim();
    }

    public static void main(String[] args) {

        String html = "<html><body>"
                + "<header>Menu trang web</header>"
                + "<h1>Thông báo học bổng HUST</h1>"
                + "<p>Sinh viên đăng ký học bổng.</p>"
                + "<footer>Thông tin liên hệ</footer>"
                + "</body></html>";

        System.out.println(cleanHtml(html));
    }
}
