package edu.hust.crawler;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HtmlDatasetAnalyzerTest {
    private static final String TEXT = "Đại học Bách khoa Hà Nội thông báo chương trình học bổng dành cho sinh viên. "
            + "Sinh viên đọc kỹ điều kiện, chuẩn bị hồ sơ và gửi đăng ký trong thời gian quy định. "
            + "Nhà trường sẽ công bố kết quả xét chọn trên trang thông tin chính thức.";

    @Test void detectsVietnameseArticleAndIgnoresNavigationWithoutMutatingHtml() {
        var document = Jsoup.parse("<title>Thông báo - HUST</title><meta property='og:type' content='article'>"
                + "<header><nav>" + "Liên kết điều hướng ".repeat(200) + "</nav></header>"
                + "<article><header><h1>Thông báo học bổng sinh viên</h1></header><div class='entry-content'><p>" + TEXT + "</p></div></article>"
                + "<footer>Footer menu</footer>");
        String original = document.outerHtml();
        var analysis = HtmlDatasetAnalyzer.analyze(document, uri("https://hust.edu.vn/vi/news/thong-bao-hoc-bong-2026.html"));
        assertEquals("ARTICLE", analysis.kind());
        assertEquals("Thông báo học bổng sinh viên", analysis.title());
        assertTrue(analysis.textLength() >= 150 && analysis.textLength() < 600);
        assertEquals(original, document.outerHtml());
        assertFalse(document.select("header nav").isEmpty());
    }

    @Test void identifiesDefaultServerSuspendedAndChallengePagesEvenWithLongText() {
        for (String title : List.of("Welcome to nginx!", "Apache2 Ubuntu Default Page", "Account Suspended",
                "Test Page for the Nginx HTTP Server on CentOS", "Just a moment...", "404 Not Found")) {
            var document = Jsoup.parse("<title>" + title + "</title><meta property='og:type' content='article'>"
                    + "<main><h1>" + title + "</h1><p>" + TEXT.repeat(3) + "</p></main>");
            assertEquals("PLACEHOLDER", HtmlDatasetAnalyzer.analyze(document, uri("https://test.hust.edu.vn/default-server.html")).kind(), title);
        }
        var challenge = Jsoup.parse("<title>HUST</title><form id='challenge-form'>Browser challenge</form><p>" + TEXT + "</p>");
        assertEquals("PLACEHOLDER", HtmlDatasetAnalyzer.analyze(challenge, uri("https://test.hust.edu.vn/article.html")).kind());
    }

    @Test void loginPagesAndProfilesCannotBecomeCleanNewsArticles() {
        var document = Jsoup.parse("<title>Thông báo HUST</title><meta property='og:type' content='article'><main><p>" + TEXT + "</p></main>");
        for (String path : List.of("/login", "/auth/authorize", "/wp-login.php", "/admin/"))
            assertEquals("ACCESS_PAGE", HtmlDatasetAnalyzer.analyze(document, uri("https://portal.hust.edu.vn" + path)).kind());
        var login = Jsoup.parse("<title>Đăng nhập - HUST</title><form><input type='password'></form><p>" + TEXT + "</p>");
        assertEquals("ACCESS_PAGE", HtmlDatasetAnalyzer.analyze(login, uri("https://portal.hust.edu.vn/index.php")).kind());
        assertEquals("ACCESS_PAGE", HtmlDatasetAnalyzer.analyze(login, uri("https://soict.hust.edu.vn/author/admin/")).kind(),
                "An authentication form still wins over the author-listing route");
        for (String path : List.of("/staff/nguyen-van-an.html", "/can-bo/ts-nguyen-van-an.html", "/pgs-ts-nguyen-van-an.html"))
            assertEquals("CONTENT_PAGE", HtmlDatasetAnalyzer.analyze(document, uri("https://soict.hust.edu.vn" + path)).kind());
        assertEquals("ARTICLE", HtmlDatasetAnalyzer.analyze(document,
                uri("https://soict.hust.edu.vn/huong-dan-dang-nhap-he-thong.html")).kind());
    }

    @Test void rootCategorySearchAndPaginationRemainListingsDespiteArticleMetadata() {
        String card = "<article><h2><a href='/article.html'>Bài viết</a></h2><p>" + TEXT + "</p></article>";
        var document = Jsoup.parse("<title>Tin tức HUST</title><meta property='og:type' content='article'><main>" + card.repeat(4) + "</main>");
        for (String path : List.of("/", "/vi/", "/vi/index.html", "/home", "/en/home/", "/category/tin-tuc/", "/tag/hoc-bong/", "/author/admin/", "/news/page/2/",
                "/news.html", "/calendar/2026/11/", "/index.php?s=hoc-bong"))
            assertEquals("LISTING", HtmlDatasetAnalyzer.analyze(document, uri("https://hust.edu.vn" + path)).kind(), path);
    }

    @Test void articleCardsDoNotBecomeAnArticleBecauseTheirListingHasAnHtmlSuffix() {
        String card = "<article><h2><a href='/article.html'>Bài viết</a></h2><div class='entry-content'><p>" + TEXT + "</p></div></article>";
        var document = Jsoup.parse("<title>Các tin mới</title><meta property='og:type' content='article'><main><h1>Tin mới</h1>"
                + card.repeat(4) + "</main>");
        var analysis = HtmlDatasetAnalyzer.analyze(document, uri("https://hust.edu.vn/cac-tin-tuc-moi-nam-2026.html"));
        assertEquals("LISTING", analysis.kind());
        assertEquals("MULTIPLE_ARTICLE_CARDS", analysis.reason());
        assertEquals("Các tin mới", analysis.title());
    }

    @Test void longHtmlDetailCanBeArticleWhileGenericContentAndShellsAreSeparate() {
        var longDetail = Jsoup.parse("<title>Thông báo đào tạo</title><main><h1>Thông báo đào tạo năm 2026</h1><p>" + TEXT.repeat(3) + "</p></main>");
        assertEquals("ARTICLE", HtmlDatasetAnalyzer.analyze(longDetail,
                uri("https://hust.edu.vn/thong-bao-dao-tao-nam-2026.html")).kind());
        var generic = Jsoup.parse("<title>Quy định</title><main><p>" + TEXT + "</p></main>");
        assertEquals("CONTENT_PAGE", HtmlDatasetAnalyzer.analyze(generic, uri("https://hust.edu.vn/documents/rules")).kind());
        assertEquals("CONTENT_PAGE", HtmlDatasetAnalyzer.analyze(longDetail, uri("https://hust.edu.vn/about.html")).kind());
        var shell = Jsoup.parse("<title>HUST App</title><div id='root'></div><script src='/app.js'>let page = '" + TEXT + "';</script>");
        assertEquals("EMPTY", HtmlDatasetAnalyzer.analyze(shell, uri("https://portal.hust.edu.vn/")).kind());
        var menuOnly = Jsoup.parse("<title>HUST</title><header><nav>" + TEXT.repeat(20) + "</nav></header>");
        assertEquals("EMPTY", HtmlDatasetAnalyzer.analyze(menuOnly, uri("https://hust.edu.vn/article-detail.html")).kind());
    }

    @Test void structuredNewsArticleJsonCanIdentifyDetailContent() {
        var document = Jsoup.parse("<title>Bài viết</title><script type='application/ld+json'>"
                + "{\"@context\":\"https://schema.org\",\"@type\":[\"NewsArticle\",\"Thing\"]}</script>"
                + "<main><h1>Bài viết mới</h1><p>" + TEXT + "</p></main>");
        assertEquals("ARTICLE", HtmlDatasetAnalyzer.analyze(document, uri("https://soict.hust.edu.vn/tin-tuc-moi-nam-2026/")).kind());
    }

    @Test void hustNukeVietBodyIdExcludesRelatedContentAndKeepsItsHeadline() {
        var document = Jsoup.parse("<title>Tin tức HUST</title><header><nav>" + TEXT.repeat(10) + "</nav></header>"
                + "<h1>Thông báo học bổng mới</h1><div id='news-bodyhtml'><p>" + TEXT + "</p></div>"
                + "<section><h2>Các tin liên quan</h2>" + TEXT.repeat(10) + "</section>");
        var analysis = HtmlDatasetAnalyzer.analyze(document, uri("https://hust.edu.vn/vi/news/thong-bao-hoc-bong-moi-655291.html"));
        assertEquals("ARTICLE", analysis.kind());
        assertEquals("Thông báo học bổng mới", analysis.title());
        assertEquals(TEXT.length(), analysis.textLength());
    }

    @Test void preservesActualLinksAndRelationsWithScopedTrackingNormalizationAndUnicodeText() {
        var document = Jsoup.parse("""
                <title>Links</title>
                <a href='/detail.html?utm_source=x&amp;id=7&amp;fbclid=foo#one'>  Học&nbsp; bổng
                sinh viên </a>
                <a href='/detail.html?id=7&amp;utm_medium=email#two'>Trùng đích</a>
                <map><area href='/detail.html?id=7' alt='Bản đồ'></map>
                <iframe src='//portal.hust.edu.vn/embed'></iframe>
                <iframe src='//portal.hust.edu.vn/embed' title='Khung thông tin'></iframe>
                <a href='https://hust.edu.vn.evil.com/'>Ngoài phạm vi</a>
                <a href='mailto:office@hust.edu.vn'>Email</a>
                <a href='javascript:alert(1)'>Script</a>
                <a href='https://hust.edu.vn:8443/'>Port khác</a>
                """);
        var analysis = HtmlDatasetAnalyzer.analyze(document, uri("https://soict.hust.edu.vn/source.html"));
        assertEquals(3, analysis.links().size());
        assertEquals(new HtmlDatasetAnalyzer.Link(uri("https://soict.hust.edu.vn/detail.html?id=7"), "Học bổng sinh viên", "anchor"), analysis.links().get(0));
        assertEquals("area", analysis.links().get(1).relation());
        assertEquals("Bản đồ", analysis.links().get(1).anchorText());
        assertEquals(new HtmlDatasetAnalyzer.Link(uri("https://portal.hust.edu.vn/embed"), "Khung thông tin", "iframe"), analysis.links().get(2));
        assertThrows(UnsupportedOperationException.class, () -> analysis.links().clear());
    }

    @Test void baseHrefIsResolvedAgainstTheFetchedUrlWithoutChangingDocument() {
        var document = Jsoup.parse("<base href='/vi/news/'><a href='article.html'>Bài viết</a>", "https://unrelated.example/");
        String original = document.outerHtml();
        var analysis = HtmlDatasetAnalyzer.analyze(document, uri("https://hust.edu.vn/source.html"));
        assertEquals(uri("https://hust.edu.vn/vi/news/article.html"), analysis.links().get(0).target());
        assertEquals(original, document.outerHtml());
    }

    private static URI uri(String value) { return URI.create(value); }
}
