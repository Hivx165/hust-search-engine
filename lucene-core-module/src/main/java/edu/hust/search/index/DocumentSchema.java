package edu.hust.search.index;

import org.apache.lucene.document.*;

/**
 * Định nghĩa cấu trúc Field của tài liệu trong Inverted Index Apache Lucene.
 * Quy định chuẩn giao tiếp chung giữa Crawler, Parser, Indexer và Searcher.
 */
public final class DocumentSchema {

    // Tên các trường chuẩn trong Lucene Document
    public static final String FIELD_ID = "id";
    public static final String FIELD_URL = "url";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SUBDOMAIN = "subdomain";
    public static final String FIELD_CRAWLED_DATE = "crawled_date";
    public static final String FIELD_FILE_TYPE = "file_type";
    public static final String FIELD_CONTENT_HASH = "content_hash";

    private DocumentSchema() {
        // Utility class
    }

    /**
     * Tạo một đối tượng Document của Lucene từ các trường dữ liệu thô.
     */
    public static Document createDocument(
            String id,
            String url,
            String title,
            String content,
            String subdomain,
            long crawledDate,
            String fileType,
            String contentHash
    ) {
        Document doc = new Document();

        if (id != null) {
            doc.add(new StringField(FIELD_ID, id, Field.Store.YES));
        }
        if (url != null) {
            doc.add(new StoredField(FIELD_URL, url));
        }
        if (title != null) {
            doc.add(new TextField(FIELD_TITLE, title, Field.Store.YES));
        }
        if (content != null) {
            doc.add(new TextField(FIELD_CONTENT, content, Field.Store.YES));
        }
        if (subdomain != null) {
            doc.add(new StringField(FIELD_SUBDOMAIN, subdomain, Field.Store.YES));
        }
        doc.add(new LongPoint(FIELD_CRAWLED_DATE, crawledDate));
        doc.add(new StoredField(FIELD_CRAWLED_DATE, crawledDate));

        if (fileType != null) {
            doc.add(new StringField(FIELD_FILE_TYPE, fileType, Field.Store.YES));
        }
        if (contentHash != null) {
            doc.add(new StoredField(FIELD_CONTENT_HASH, contentHash));
        }

        return doc;
    }
}
