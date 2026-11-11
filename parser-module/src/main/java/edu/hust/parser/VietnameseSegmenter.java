
package edu.hust.parser;

import vn.pipeline.VnCoreNLP;
import vn.pipeline.Annotation;
import vn.pipeline.Sentence;
import java.io.IOException;

public class VietnameseSegmenter {

    private final VnCoreNLP pipeline;

    public VietnameseSegmenter() throws IOException {
        // Chỉ sử dụng chức năng tách từ
        pipeline = new VnCoreNLP(new String[]{"wseg"});
    }

    public String segmentText(String text) throws IOException {

        if (text == null || text.isBlank()) {
            return "";
        }

        Annotation annotation = new Annotation(text);
        pipeline.annotate(annotation);

        StringBuilder result = new StringBuilder();

        for (Sentence sentence : annotation.getSentences()) {
            if (result.length() > 0) {
                result.append(" ");
            }
            result.append(sentence.getWordSegmentedSentence());
        }

        return result.toString().trim();
    }

    public static void main(String[] args) throws IOException {

        VietnameseSegmenter segmenter =
                new VietnameseSegmenter();

        String text = "Sinh viên Đại học Bách khoa Hà Nội "
                    + "đăng ký học bổng khuyến khích học tập.";

        System.out.println("TRUOC KHI TACH TU:");
        System.out.println(text);

        System.out.println("SAU KHI TACH TU:");
        System.out.println(segmenter.segmentText(text));
    }
}
