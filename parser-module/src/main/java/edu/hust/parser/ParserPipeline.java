
package edu.hust.parser;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

public class ParserPipeline {

    public static void main(String[] args) throws Exception {

        if (args.length == 0) {
            System.out.println("Chua cung cap duong dan file!");
            return;
        }

        Path path = Path.of(args[0]);
        String name = path.getFileName()
                          .toString()
                          .toLowerCase(Locale.ROOT);

        String text;

        // Bước 1: Đọc dữ liệu
        if (name.endsWith(".html")) {
            String html = Files.readString(path);
            text = HtmlContentCleaner.cleanHtml(html);

        } else if (name.endsWith(".pdf")
                || name.endsWith(".docx")) {

            try (InputStream stream = Files.newInputStream(path)) {
                text = TikaDocumentExtractor
                        .extractTextFromStream(stream);
            }

        } else {
            System.out.println("Dinh dang file chua ho tro!");
            return;
        }

        // Bước 2: Chuẩn hóa khoảng trắng
        text = text.replaceAll("\\s+", " ").trim();

        // Bước 3: Tách từ tiếng Việt
        VietnameseSegmenter segmenter =
                new VietnameseSegmenter();

        String segmented = segmenter.segmentText(text);

        // Bước 4: Lọc từ dừng
        StopwordFilter filter = new StopwordFilter();
        String filtered = filter.removeStopwords(segmented);

        System.out.println("VAN BAN SACH:");
        System.out.println(text);

        System.out.println("\nSAU KHI TACH TU:");
        System.out.println(segmented);

        System.out.println("\nSAU KHI LOC STOPWORDS:");
        System.out.println(filtered);
    }
}
