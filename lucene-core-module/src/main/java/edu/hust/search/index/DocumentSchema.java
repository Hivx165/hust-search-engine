package edu.hust.search.index;

import edu.hust.search.model.DocumentModel;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;

import java.time.Instant;
import java.util.Objects;

/**
 * Standard Document Schema definition for Lucene Core (Member 3).
 * Maps DocumentModel to Lucene Document using modern Lucene 9.x+ APIs.
 */
public final class DocumentSchema {

    public static final String FIELD_ID = "id";
    public static final String FIELD_URL = "url";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_CONTENT = "content";
    public static final String FIELD_SUBDOMAIN = "subdomain";
    public static final String FIELD_FILE_TYPE = "file_type";
    public static final String FIELD_CRAWLED_DATE = "crawled_date";
    public static final String FIELD_CONTENT_HASH = "content_hash";

    private DocumentSchema() {
        // Private constructor for utility class
    }

    /**
     * Converts a DocumentModel into an Apache Lucene Document adhering strictly
     * to the 8-field architectural schema specification.
     *
     * @param model Domain data transfer object
     * @return Fully mapped Lucene Document
     */
    public static Document toLuceneDocument(DocumentModel model) {
        Objects.requireNonNull(model, "DocumentModel must not be null");
        Objects.requireNonNull(model.getId(), "Document ID must not be null for indexing");

        Document doc = new Document();

        // 1. id: StringField (exact keyword, stored). Used as primary key for updates
        doc.add(new StringField(FIELD_ID, model.getId(), Field.Store.YES));

        // 2. url: StoredField (stored for UI presentation only, not indexed)
        doc.add(new StoredField(FIELD_URL, model.getUrl() != null ? model.getUrl() : ""));

        // 3. title: TextField (analyzed full-text, stored for UI display & query boosting)
        doc.add(new TextField(FIELD_TITLE, model.getTitle() != null ? model.getTitle() : "", Field.Store.YES));

        // 4. content: TextField (analyzed full-text, STORED.YES is required for Member 4 Highlighter)
        doc.add(new TextField(FIELD_CONTENT, model.getContent() != null ? model.getContent() : "", Field.Store.YES));

        // 5. subdomain: StringField (exact keyword, stored for filtering by school/department)
        doc.add(new StringField(FIELD_SUBDOMAIN, model.getSubdomain() != null ? model.getSubdomain() : "", Field.Store.YES));

        // 6. file_type: StringField (exact keyword: HTML, PDF, DOCX)
        doc.add(new StringField(FIELD_FILE_TYPE, model.getFileType() != null ? model.getFileType() : "HTML", Field.Store.YES));

        // 7. crawled_date: Multi-field for modern Lucene (Point + Stored + DocValues)
        long epochMillis = model.getCrawledEpochMillis();
        // A) LongPoint: For BKD-tree range filter queries (LongPoint.newRangeQuery)
        doc.add(new LongPoint(FIELD_CRAWLED_DATE, epochMillis));
        // B) StoredField: Retrieval for UI display
        doc.add(new StoredField(FIELD_CRAWLED_DATE, epochMillis));
        // C) NumericDocValuesField: Columnar storage for fast sorting and aggregation
        doc.add(new NumericDocValuesField(FIELD_CRAWLED_DATE, epochMillis));

        // 8. content_hash: StoredField (stored for crawler change-detection and audit)
        doc.add(new StoredField(FIELD_CONTENT_HASH, model.getContentHash() != null ? model.getContentHash() : ""));

        return doc;
    }

    /**
     * Helper to reconstruct a DocumentModel from stored fields in a Lucene hit Document.
     * Useful for search service retrieval (Member 4) and verification.
     */
    public static DocumentModel fromLuceneDocument(Document doc) {
        if (doc == null) {
            return null;
        }

        DocumentModel model = new DocumentModel();
        model.setId(doc.get(FIELD_ID));
        model.setUrl(doc.get(FIELD_URL));
        model.setTitle(doc.get(FIELD_TITLE));
        model.setContent(doc.get(FIELD_CONTENT));
        model.setSubdomain(doc.get(FIELD_SUBDOMAIN));
        model.setFileType(doc.get(FIELD_FILE_TYPE));
        model.setContentHash(doc.get(FIELD_CONTENT_HASH));

        Number dateVal = doc.getField(FIELD_CRAWLED_DATE) != null ? doc.getField(FIELD_CRAWLED_DATE).numericValue() : null;
        if (dateVal != null) {
            model.setCrawledAt(Instant.ofEpochMilli(dateVal.longValue()).toString());
        }

        return model;
    }
}
