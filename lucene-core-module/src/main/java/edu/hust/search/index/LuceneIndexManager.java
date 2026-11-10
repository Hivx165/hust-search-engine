package edu.hust.search.index;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import edu.hust.search.model.DocumentModel;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.SearcherManager;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Thread-safe Index Manager for Apache Lucene 9.x/10.x.
 * Built for Member 3 (Index Core) of hust-search-engine.
 *
 * Key Capabilities:
 * - High-speed indexing with 256MB RAM buffer.
 * - Idempotent upsert via {@link IndexWriter#updateDocument(Term, Iterable)}.
 * - High-throughput batch indexing (1000+ docs).
 * - Direct ingestion from JSON / JSONL output files of parser-module.
 * - Integration-ready SearcherManager for search and query modules (Member 4).
 */
public class LuceneIndexManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LuceneIndexManager.class);
    public static final double DEFAULT_RAM_BUFFER_SIZE_MB = 256.0;

    private final Path indexPath;
    private final Directory directory;
    private final VietnameseLuceneAnalyzer analyzer;
    private final IndexWriter indexWriter;
    private final SearcherManager searcherManager;
    private final ObjectMapper objectMapper;
    private final AtomicBoolean isClosed = new AtomicBoolean(false);

    /**
     * Default constructor opening index in standard location "storage/lucene_index".
     */
    public LuceneIndexManager() throws IOException {
        this(Paths.get("storage", "lucene_index"), new VietnameseLuceneAnalyzer());
    }

    /**
     * Constructor with custom index storage directory.
     */
    public LuceneIndexManager(Path indexPath) throws IOException {
        this(indexPath, new VietnameseLuceneAnalyzer());
    }

    /**
     * Constructor with custom index directory and analyzer.
     */
    public LuceneIndexManager(Path indexPath, VietnameseLuceneAnalyzer analyzer) throws IOException {
        this.indexPath = Objects.requireNonNull(indexPath, "Index path must not be null");
        this.analyzer = Objects.requireNonNull(analyzer, "Analyzer must not be null");

        // Ensure directory exists on filesystem
        if (!Files.exists(indexPath)) {
            Files.createDirectories(indexPath);
        }

        this.directory = FSDirectory.open(indexPath);

        IndexWriterConfig config = new IndexWriterConfig(this.analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE_OR_APPEND);
        config.setRAMBufferSizeMB(DEFAULT_RAM_BUFFER_SIZE_MB);

        this.indexWriter = new IndexWriter(this.directory, config);

        // SearcherManager manages near-real-time IndexSearcher instances for downstream modules
        this.searcherManager = new SearcherManager(this.indexWriter, true, true, null);

        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        log.info("Initialized LuceneIndexManager at '{}' with RAM buffer {} MB",
                indexPath.toAbsolutePath(), DEFAULT_RAM_BUFFER_SIZE_MB);
    }

    /**
     * Inserts a new document or updates an existing document with matching ID.
     * Prevents duplicate documents during re-indexing.
     *
     * @param doc The document to insert or update
     * @throws IOException on I/O error
     */
    public void upsertDocument(DocumentModel doc) throws IOException {
        ensureOpen();
        Objects.requireNonNull(doc, "DocumentModel cannot be null");
        Objects.requireNonNull(doc.getId(), "Document id cannot be null");

        Term idTerm = new Term(DocumentSchema.FIELD_ID, doc.getId());
        Document luceneDoc = DocumentSchema.toLuceneDocument(doc);
        indexWriter.updateDocument(idTerm, luceneDoc);
    }

    /**
     * Ingests a collection of documents in a high-throughput batch.
     * Commits to disk once upon batch completion.
     *
     * @param docs List of documents to index
     * @return Number of documents processed in the batch
     * @throws IOException on I/O error
     */
    public int indexBatch(List<DocumentModel> docs) throws IOException {
        ensureOpen();
        if (docs == null || docs.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (DocumentModel doc : docs) {
            if (doc != null && doc.getId() != null) {
                Term idTerm = new Term(DocumentSchema.FIELD_ID, doc.getId());
                indexWriter.updateDocument(idTerm, DocumentSchema.toLuceneDocument(doc));
                count++;
            }
        }

        // Commit batch writes to disk
        commit();
        log.info("Successfully indexed batch of {} documents to Lucene index", count);
        return count;
    }

    /**
     * Reads and imports documents from a JSON or JSONL file delivered by parser-module (Member 2).
     * Automatically detects standard JSON array vs. JSON Lines format.
     *
     * @param jsonPath Path to the input JSON / JSONL file
     * @return Number of documents successfully ingested
     * @throws IOException on file or parsing error
     */
    public int importFromJsonFile(Path jsonPath) throws IOException {
        ensureOpen();
        Objects.requireNonNull(jsonPath, "JSON file path cannot be null");
        if (!Files.exists(jsonPath)) {
            throw new IllegalArgumentException("File does not exist: " + jsonPath.toAbsolutePath());
        }

        List<DocumentModel> documents = new ArrayList<>();

        try (BufferedReader reader = Files.newBufferedReader(jsonPath, StandardCharsets.UTF_8)) {
            reader.mark(4096);
            int firstChar = -1;
            int c;
            while ((c = reader.read()) != -1) {
                if (!Character.isWhitespace((char) c)) {
                    firstChar = c;
                    break;
                }
            }
            reader.reset();

            if (firstChar == '[') {
                // Format: Standard JSON array [ {...}, {...} ]
                documents = objectMapper.readValue(reader, new TypeReference<List<DocumentModel>>() {});
            } else {
                // Format: JSON Lines (one JSON object per line)
                String line;
                while ((line = reader.readLine()) != null) {
                    line = line.trim();
                    if (!line.isEmpty()) {
                        DocumentModel doc = objectMapper.readValue(line, DocumentModel.class);
                        documents.add(doc);
                    }
                }
            }
        }

        return indexBatch(documents);
    }

    /**
     * Deletes a document by its primary ID.
     *
     * @param id Document primary ID
     * @throws IOException on I/O error
     */
    public void deleteDocumentById(String id) throws IOException {
        ensureOpen();
        Objects.requireNonNull(id, "ID cannot be null");
        indexWriter.deleteDocuments(new Term(DocumentSchema.FIELD_ID, id));
    }

    /**
     * Retrieves current index statistics (number of live documents, max docs, deleted docs).
     */
    public IndexStats getIndexStats() {
        ensureOpen();
        IndexWriter.DocStats stats = indexWriter.getDocStats();
        int numDocs = stats.numDocs;
        int maxDoc = stats.maxDoc;
        int numDeletedDocs = Math.max(0, maxDoc - numDocs);
        return new IndexStats(numDocs, maxDoc, numDeletedDocs);
    }

    /**
     * Flushes RAM buffers to disk and refreshes the searcher manager.
     */
    public synchronized void commit() throws IOException {
        ensureOpen();
        indexWriter.commit();
        searcherManager.maybeRefresh();
    }

    /**
     * Provides access to SearcherManager for downstream query operations.
     */
    public SearcherManager getSearcherManager() {
        ensureOpen();
        return searcherManager;
    }

    public IndexWriter getIndexWriter() {
        ensureOpen();
        return indexWriter;
    }

    public VietnameseLuceneAnalyzer getAnalyzer() {
        return analyzer;
    }

    public Path getIndexPath() {
        return indexPath;
    }

    public Directory getDirectory() {
        return directory;
    }

    private void ensureOpen() {
        if (isClosed.get()) {
            throw new IllegalStateException("LuceneIndexManager has already been closed.");
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (isClosed.compareAndSet(false, true)) {
            log.info("Closing LuceneIndexManager...");
            try {
                searcherManager.close();
            } catch (Exception e) {
                log.warn("Error closing SearcherManager: {}", e.getMessage());
            } finally {
                try {
                    indexWriter.commit();
                } catch (Exception e) {
                    log.warn("Error committing IndexWriter during close: {}", e.getMessage());
                } finally {
                    try {
                        indexWriter.close();
                    } finally {
                        try {
                            directory.close();
                        } finally {
                            analyzer.close();
                        }
                    }
                }
            }
            log.info("LuceneIndexManager closed successfully and lock released.");
        }
    }
}
