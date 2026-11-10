package edu.hust.search.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.hust.search.model.DocumentModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Utility generator for realistic mock Vietnamese segmented documents.
 * Enables Member 3 (Index), Member 4 (Search), and Member 5 (Evaluation)
 * to run offline and test 1,000+ documents before crawler/parser integration.
 */
public class MockDatasetGenerator {

    private static final String[] SUBDOMAINS = {
            "soict.hust.edu.vn",
            "sami.hust.edu.vn",
            "cfl.hust.edu.vn",
            "chemeng.hust.edu.vn",
            "seee.hust.edu.vn",
            "sme.hust.edu.vn",
            "ctt.hust.edu.vn"
    };

    private static final String[] FILE_TYPES = {"HTML", "PDF", "DOCX"};

    private static final String[] TITLES = {
            "Thông_báo đăng_ký đồ_án tốt_nghiệp kỳ 2023-2 cho sinh_viên K64",
            "Danh_sách xét cấp học_bổng khuyến_khích học_tập kỳ 2023-1 sinh_viên",
            "Kế_hoạch tổ_chức hội_nghị nghiên_cứu khoa_học sinh_viên Bách_Khoa năm 2024",
            "Lịch thi chuẩn đầu_ra ngoại_ngữ tiếng_Anh cho sinh_viên hệ đại_trà",
            "Thông_báo thời_khóa_biểu và đăng_ký học_phần kỳ hè năm_học 2023-2024",
            "Chương_trình thực_tập doanh_nghiệp công_nghệ_thông_tin tại tập_đoàn công_nghệ",
            "Quyết_định công_nhận tốt_nghiệp và trao bằng kỹ_sư cử_nhân năm 2024",
            "Hội_thảo trí_tuệ_nhân_tạo và ứng_dụng dữ_liệu_lớn trong chuyển_đổi_số",
            "Quy_định về chuẩn_mực học_thuật và liêm_chính khoa_học trong đồ_án",
            "Lễ bế_giảng và trao học_bổng doanh_nghiệp tài_trợ cho tân sinh_viên K68"
    };

    private static final String[] CONTENT_TEMPLATES = {
            "Viện thông_báo đến toàn_thể sinh_viên về việc nộp đề_cương đồ_án tốt_nghiệp. " +
                    "Sinh_viên cần chủ_động liên_hệ giảng_viên hướng_dẫn để hoàn_thiện hồ_sơ đúng hạn và tuân_thủ quy_chế đào_tạo của nhà_trường.",

            "Hội_đồng xét duyệt thông_báo danh_sách sinh_viên đạt điểm rèn_luyện xuất_sắc và điểm học_tập cao " +
                    "được nhận học_bổng khuyến_khích học_tập. Tiền học_bổng sẽ được chuyển trực_tiếp vào tài_khoản ngân_hàng của sinh_viên.",

            "Trường Đại_học Bách_Khoa Hà_Nội phát_động phong_trào nghiên_cứu khoa_học sinh_viên. " +
                    "Các đề_tài xuất_sắc thuộc lĩnh_vực trí_tuệ_nhân_tạo, thị_giác máy_tính và xử_lý ngôn_ngữ tự_nhiên sẽ được tài_trợ kinh_phí.",

            "Phòng Đào_tạo thông_báo kế_hoạch đăng_ký học_tập qua hệ_thống thông_tin ctt.hust.edu.vn. " +
                    "Sinh_viên cần kiểm_tra kỹ thời_khóa_biểu, mã học_phần và điều_kiện tiên_quyết trước khi bấm xác_nhận.",

            "Trung_tâm Ngoại_ngữ phối_hợp với các viện tổ_chức kỳ_thi đánh_giá năng_lực tiếng_Anh nội_bộ. " +
                    "Kết_quả bài thi là căn_cứ xét điều_kiện nhận đồ_án tốt_nghiệp và chuẩn đầu_ra cho sinh_viên các khóa K64, K65, K66."
    };

    public static List<DocumentModel> generateMockDocuments(int count) {
        List<DocumentModel> list = new ArrayList<>(count);
        Random random = new Random(42); // Deterministic seed for reproducible testing
        Instant baseTime = Instant.parse("2026-10-01T08:00:00Z");

        for (int i = 1; i <= count; i++) {
            String subdomain = SUBDOMAINS[random.nextInt(SUBDOMAINS.length)];
            String fileType = FILE_TYPES[random.nextInt(FILE_TYPES.length)];
            String baseTitle = TITLES[random.nextInt(TITLES.length)];
            String baseContent = CONTENT_TEMPLATES[random.nextInt(CONTENT_TEMPLATES.length)];

            String title = baseTitle + " - Mã số tài_liệu " + i;
            String url = String.format("https://%s/tin-tuc/thong-bao-chi-tiet-%04d.%s",
                    subdomain, i, fileType.equalsIgnoreCase("HTML") ? "html" : fileType.toLowerCase());

            String content = baseContent + " Nội_dung bổ_sung bài viết số " + i +
                    ": sinh_viên Bách_Khoa nỗ_lực rèn_luyện và phấn_đấu đạt thành_tích cao trong học_tập.";

            String id = computeSha256(url);
            String contentHash = computeSha256(content);
            Instant crawledTime = baseTime.plus(i * 15L, ChronoUnit.MINUTES);

            DocumentModel doc = new DocumentModel(
                    id,
                    url,
                    title,
                    subdomain,
                    content,
                    fileType,
                    crawledTime.toString(),
                    contentHash
            );
            list.add(doc);
        }
        return list;
    }

    public static void exportToJsonFile(List<DocumentModel> docs, Path outputPath) throws IOException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        if (outputPath.getParent() != null && !Files.exists(outputPath.getParent())) {
            Files.createDirectories(outputPath.getParent());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(outputPath.toFile(), docs);
    }

    private static String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("SHA-256 computation failure", e);
        }
    }
}
