package edu.hust.search.index;

import java.nio.file.Path;
import java.nio.file.Paths;

public class MainIndexer {

    public static void main(String[] args) {
        Path jsonFilePath = Paths.get("storage", "json_data", "parsed_documents.json");

        try (LuceneIndexManager indexManager = new LuceneIndexManager()) {

            System.out.println("[1/3] Đang nạp dữ liệu từ file JSON: " + jsonFilePath.getFileName());

            int indexedCount = indexManager.importFromJsonFile(jsonFilePath);

            System.out.println("[2/3] Đã đẩy thành công " + indexedCount + " tài liệu vào Lucene Index.");

            IndexStats stats = indexManager.getIndexStats();
            System.out.println("[3/3] HOÀN TẤT! Tổng số tài liệu đang hoạt động: " + stats.numDocs());

        } catch (Exception e) {
            System.err.println("Đã xảy ra lỗi trong quá trình đọc JSON và Index: " + e.getMessage());
            e.printStackTrace();
        }
    }
}