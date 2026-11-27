package edu.hust.search.index;

import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import edu.hust.search.model.DocumentModel;
import edu.hust.search.util.MockDatasetGenerator;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TopDocs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Lucene Index Core - Member 3 Test Suite")
class LuceneIndexManagerTest {

    @Test
    @DisplayName("1. VietnameseLuceneAnalyzer: Preserves '_' compound words and filters stopwords & punctuation")
    void testVietnameseLuceneAnalyzer() throws IOException {
        try (VietnameseLuceneAnalyzer analyzer = new VietnameseLuceneAnalyzer()) {
            // Text containing Vietnamese compound words, stopwords (và, của, là), and punctuation
            String input = "Thông_báo: học_bổng khuyến_khích học_tập và đồ_án tốt_nghiệp của sinh_viên Bách_Khoa là rất quan_trọng!";
            List<String> tokens = new ArrayList<>();

            try (TokenStream stream = analyzer.tokenStream("content", new StringReader(input))) {
                CharTermAttribute termAtt = stream.addAttribute(CharTermAttribute.class);
                stream.reset();
                while (stream.incrementToken()) {
                    tokens.add(termAtt.toString());
                }
                stream.end();
            }

            // Verify compound words preserved with '_' intact
            assertTrue(tokens.contains("thông_báo"), "Must preserve 'thông_báo'");
            assertTrue(tokens.contains("học_bổng"), "Must preserve 'học_bổng'");
            assertTrue(tokens.contains("khuyến_khích"), "Must preserve 'khuyến_khích'");
            assertTrue(tokens.contains("học_tập"), "Must preserve 'học_tập'");
            assertTrue(tokens.contains("đồ_án"), "Must preserve 'đồ_án'");
            assertTrue(tokens.contains("tốt_nghiệp"), "Must preserve 'tốt_nghiệp'");
            assertTrue(tokens.contains("sinh_viên"), "Must preserve 'sinh_viên'");
            assertTrue(tokens.contains("bách_khoa"), "Must preserve 'bách_khoa'");
            assertTrue(tokens.contains("quan_trọng"), "Must preserve 'quan_trọng'");

            // Verify stopwords are stripped
            assertFalse(tokens.contains("và"), "Stopword 'và' must be filtered out");
            assertFalse(tokens.contains("của"), "Stopword 'của' must be filtered out");
            assertFalse(tokens.contains("là"), "Stopword 'là' must be filtered out");
            assertFalse(tokens.contains("rất"), "Stopword 'rất' must be filtered out");

            // Verify punctuation was stripped from tokens
            assertFalse(tokens.contains("thông_báo:"), "Trailing colon must be stripped");
            assertFalse(tokens.contains("quan_trọng!"), "Trailing exclamation must be stripped");
        }
    }

    @Test
    @DisplayName("2. Batch Indexing: Indexes 1,050 documents and checks numDocs == 1050")
    void testBatchIndexing1050Documents(@TempDir Path tempDir) throws Exception {
        List<DocumentModel> mockDocs = MockDatasetGenerator.generateMockDocuments(1050);
        assertEquals(1050, mockDocs.size(), "Generated mock dataset must contain 1050 records");

        try (LuceneIndexManager indexManager = new LuceneIndexManager(tempDir)) {
            int indexedCount = indexManager.indexBatch(mockDocs);
            assertEquals(1050, indexedCount, "Must index exactly 1050 documents");

            IndexStats stats = indexManager.getIndexStats();
            assertEquals(1050, stats.numDocs(), "Index stats numDocs must equal 1050");
            assertEquals(1050, stats.maxDoc(), "Index stats maxDoc must equal 1050");
            assertFalse(stats.hasDeletions(), "New batch must not have deletions");
        }
    }

    @Test
    @DisplayName("3. Idempotent Upsert: Updating existing ID does not increase document count")
    void testUpsertPreventsDuplicates(@TempDir Path tempDir) throws Exception {
        List<DocumentModel> mockDocs = MockDatasetGenerator.generateMockDocuments(1050);

        try (LuceneIndexManager indexManager = new LuceneIndexManager(tempDir)) {
            indexManager.indexBatch(mockDocs);
            assertEquals(1050, indexManager.getIndexStats().numDocs());

            // Pick doc 0 and update its title and content
            DocumentModel targetDoc = mockDocs.get(0);
            DocumentModel updatedDoc = new DocumentModel(
                    targetDoc.getId(),
                    targetDoc.getUrl(),
                    "TIÊU ĐỀ ĐÃ ĐƯỢC CẬP NHẬT CHO SINH VIÊN K64",
                    targetDoc.getSubdomain(),
                    "Nội dung cập nhật mới nhất cho sinh_viên Bách_Khoa.",
                    targetDoc.getFileType(),
                    Instant.now().toString(),
                    "new_hash_9999"
            );

            // Upsert the document
            indexManager.upsertDocument(updatedDoc);
            indexManager.commit();

            // Total documents must remain exactly 1050
            IndexStats stats = indexManager.getIndexStats();
            assertEquals(1050, stats.numDocs(), "numDocs must remain 1050 after updating an existing document ID");

            // Re-read document from index to ensure updated content is stored
            try (IndexReader reader = DirectoryReader.open(indexManager.getDirectory())) {
                IndexSearcher searcher = new IndexSearcher(reader);
                TopDocs hits = searcher.search(new org.apache.lucene.search.TermQuery(new org.apache.lucene.index.Term("id", targetDoc.getId())), 1);
                assertEquals(1, hits.totalHits.value);
                var storedDoc = searcher.doc(hits.scoreDocs[0].doc);
                assertEquals("TIÊU ĐỀ ĐÃ ĐƯỢC CẬP NHẬT CHO SINH VIÊN K64", storedDoc.get(DocumentSchema.FIELD_TITLE));
                assertEquals("new_hash_9999", storedDoc.get(DocumentSchema.FIELD_CONTENT_HASH));
            }
        }
    }

    @Test
    @DisplayName("4. Import from JSON File: Reads JSON array and populates index")
    void testImportFromJsonFile(@TempDir Path tempDir) throws Exception {
        Path jsonFile = tempDir.resolve("parser_output.json");
        Path indexDir = tempDir.resolve("lucene_index");

        List<DocumentModel> mockDocs = MockDatasetGenerator.generateMockDocuments(150);
        MockDatasetGenerator.exportToJsonFile(mockDocs, jsonFile);
        assertTrue(jsonFile.toFile().exists());

        try (LuceneIndexManager indexManager = new LuceneIndexManager(indexDir)) {
            int imported = indexManager.importFromJsonFile(jsonFile);
            assertEquals(150, imported);
            assertEquals(150, indexManager.getIndexStats().numDocs());
        }
    }

    @Test
    @DisplayName("5. Numeric Field Verification: LongPoint range query and DocValues sorting on crawled_date")
    void testNumericRangeAndDocValuesSort(@TempDir Path tempDir) throws Exception {
        List<DocumentModel> docs = new ArrayList<>();
        Instant t1 = Instant.parse("2026-10-01T00:00:00Z");
        Instant t2 = Instant.parse("2026-10-15T00:00:00Z");
        Instant t3 = Instant.parse("2026-10-30T00:00:00Z");

        docs.add(new DocumentModel("doc1", "https://soict.hust.edu.vn/1", "Bài 1", "soict.hust.edu.vn", "Nội dung 1", "HTML", t1.toString(), "h1"));
        docs.add(new DocumentModel("doc2", "https://soict.hust.edu.vn/2", "Bài 2", "soict.hust.edu.vn", "Nội dung 2", "HTML", t2.toString(), "h2"));
        docs.add(new DocumentModel("doc3", "https://soict.hust.edu.vn/3", "Bài 3", "soict.hust.edu.vn", "Nội dung 3", "HTML", t3.toString(), "h3"));

        try (LuceneIndexManager manager = new LuceneIndexManager(tempDir)) {
            manager.indexBatch(docs);

            try (IndexReader reader = DirectoryReader.open(manager.getDirectory())) {
                IndexSearcher searcher = new IndexSearcher(reader);

                // Range query: Between t1 and t2 (inclusive) should match doc1 and doc2
                Query rangeQuery = LongPoint.newRangeQuery(DocumentSchema.FIELD_CRAWLED_DATE, t1.toEpochMilli(), t2.toEpochMilli());
                TopDocs hits = searcher.search(rangeQuery, 10);
                assertEquals(2, hits.totalHits.value, "Range query between t1 and t2 should match exactly 2 documents");

                // Sort descending by crawled_date using NumericDocValuesField
                Sort sortDesc = new Sort(new SortField(DocumentSchema.FIELD_CRAWLED_DATE, SortField.Type.LONG, true));
                TopDocs sortedHits = searcher.search(new org.apache.lucene.search.MatchAllDocsQuery(), 10, sortDesc);
                assertEquals(3, sortedHits.totalHits.value);

                // Most recent document (doc3 - t3) must be first
                assertEquals("doc3", searcher.doc(sortedHits.scoreDocs[0].doc).get(DocumentSchema.FIELD_ID));
                assertEquals("doc2", searcher.doc(sortedHits.scoreDocs[1].doc).get(DocumentSchema.FIELD_ID));
                assertEquals("doc1", searcher.doc(sortedHits.scoreDocs[2].doc).get(DocumentSchema.FIELD_ID));
            }
        }
    }

    @Test
    @DisplayName("6. Delete by ID: Safely removes document from index")
    void testDeleteDocumentById(@TempDir Path tempDir) throws Exception {
        List<DocumentModel> docs = MockDatasetGenerator.generateMockDocuments(5);

        try (LuceneIndexManager manager = new LuceneIndexManager(tempDir)) {
            manager.indexBatch(docs);
            assertEquals(5, manager.getIndexStats().numDocs());

            String targetId = docs.get(0).getId();
            manager.deleteDocumentById(targetId);
            manager.commit();

            IndexStats stats = indexManagerStats(manager);
            assertEquals(4, stats.numDocs(), "numDocs should decrease by 1");
            assertEquals(1, stats.numDeletedDocs(), "numDeletedDocs should be 1");
        }
    }

    private IndexStats indexManagerStats(LuceneIndexManager manager) {
        return manager.getIndexStats();
    }
}
