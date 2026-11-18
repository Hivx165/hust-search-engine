package edu.hust.search.query;

import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import edu.hust.search.index.DocumentSchema;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.search.*;
import org.apache.lucene.search.highlight.*;
import org.apache.lucene.search.similarities.BM25Similarity;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Dịch vụ cốt lõi xử lý tìm kiếm dữ liệu bằng Apache Lucene.
 * Hỗ trợ xếp hạng BM25, phân trang, lọc subdomain và bôi đậm từ khóa bằng Lucene Highlighter.
 */
public class LuceneSearchService implements Closeable {

    private final Directory directory;
    private final boolean ownsDirectory;
    private IndexReader reader;
    private IndexSearcher searcher;
    private final Analyzer analyzer;
    private final QueryBuilderHelper queryBuilderHelper;

    public static final int DEFAULT_MAX_FRAGMENT_SIZE = 160;
    public static final String HIGHLIGHT_PRE_TAG = "<mark>";
    public static final String HIGHLIGHT_POST_TAG = "</mark>";

    /**
     * Khởi tạo với đường dẫn thư mục Index trên ổ đĩa.
     */
    public LuceneSearchService(String indexPath) throws IOException {
        this(Paths.get(indexPath));
    }

    public LuceneSearchService(Path indexPath) throws IOException {
        this(FSDirectory.open(indexPath), true);
    }

    /**
     * Khởi tạo trực tiếp với Lucene Directory (hỗ trợ cả FSDirectory và ByteBuffersDirectory trong test).
     */
    public LuceneSearchService(Directory directory) throws IOException {
        this(directory, false);
    }

    public LuceneSearchService(Directory directory, boolean ownsDirectory) throws IOException {
        this(directory, ownsDirectory, new VietnameseLuceneAnalyzer());
    }

    public LuceneSearchService(Directory directory, boolean ownsDirectory, Analyzer analyzer) throws IOException {
        this.directory = directory;
        this.ownsDirectory = ownsDirectory;
        this.analyzer = analyzer != null ? analyzer : new VietnameseLuceneAnalyzer();
        this.queryBuilderHelper = new QueryBuilderHelper(this.analyzer);
        refreshReader();
    }

    /**
     * Làm mới IndexReader khi có dữ liệu mới được nạp vào chỉ mục.
     */
    public synchronized void refreshReader() throws IOException {
        if (reader == null) {
            reader = DirectoryReader.open(directory);
        } else {
            IndexReader newReader = DirectoryReader.openIfChanged((DirectoryReader) reader);
            if (newReader != null) {
                reader.close();
                reader = newReader;
            }
        }
        searcher = new IndexSearcher(reader);
        // Thiết lập thuật toán xếp hạng BM25 chuẩn
        searcher.setSimilarity(new BM25Similarity());
    }

    /**
     * Tìm kiếm Top-K cơ bản với từ khóa chuỗi.
     */
    public List<SearchResultDTO> search(String queryText, int topK) throws Exception {
        return searchWithFilter(queryText, null, topK);
    }

    /**
     * Tìm kiếm Top-K có kết hợp lọc subdomain.
     * Mặc định yêu cầu khớp tất cả các từ trong truy vấn (matchAllTerms = true).
     */
    public List<SearchResultDTO> searchWithFilter(String queryText, String subdomain, int topK) throws Exception {
        return searchWithFilter(queryText, subdomain, topK, true);
    }

    public List<SearchResultDTO> searchWithFilter(String queryText, String subdomain, int topK, boolean matchAllTerms) throws Exception {
        Query query = queryBuilderHelper.buildSearchQuery(queryText, subdomain, matchAllTerms);
        return executeSearch(query, topK);
    }

    /**
     * Tìm kiếm nâng cao kết hợp MUST và SHOULD (Logic cốt lõi của TV4).
     *
     * @param mustKeyword   Từ khóa bắt buộc có
     * @param shouldKeyword Từ khóa phụ trợ tăng điểm (Title Boost = 2.0)
     * @param subdomain     Lọc theo subdomain (có thể null)
     * @param topK          Số lượng kết quả tối đa
     */
    public List<SearchResultDTO> searchAdvanced(String mustKeyword, String shouldKeyword, String subdomain, int topK) throws Exception {
        Query query = queryBuilderHelper.buildAdvancedQuery(mustKeyword, shouldKeyword, subdomain);
        return executeSearch(query, topK);
    }

    /**
     * Tìm kiếm phân trang (hỗ trợ REST API và Web UI).
     *
     * @param queryText Từ khóa tìm kiếm
     * @param subdomain Lọc subdomain
     * @param page      Số trang (bắt đầu từ 1)
     * @param pageSize  Số kết quả mỗi trang
     */
    public SearchResponseDTO searchPaged(String queryText, String subdomain, int page, int pageSize) throws Exception {
        return searchPaged(queryText, subdomain, page, pageSize, true);
    }

    public SearchResponseDTO searchPaged(String queryText, String subdomain, int page, int pageSize, boolean matchAllTerms) throws Exception {
        long startTime = System.currentTimeMillis();
        int validPage = Math.max(1, page);
        int validPageSize = Math.max(1, pageSize);
        int topK = validPage * validPageSize;

        Query query = queryBuilderHelper.buildSearchQuery(queryText, subdomain, matchAllTerms);
        TopDocs topDocs = searcher.search(query, topK);

        long totalHits = topDocs.totalHits.value;
        int startIndex = (validPage - 1) * validPageSize;

        List<SearchResultDTO> pagedResults = new ArrayList<>();
        if (startIndex < topDocs.scoreDocs.length) {
            int endIndex = Math.min(startIndex + validPageSize, topDocs.scoreDocs.length);
            ScoreDoc[] slice = new ScoreDoc[endIndex - startIndex];
            System.arraycopy(topDocs.scoreDocs, startIndex, slice, 0, slice.length);
            pagedResults = buildResults(query, slice);
        }

        long executionTimeMs = System.currentTimeMillis() - startTime;
        return new SearchResponseDTO(queryText, totalHits, validPage, validPageSize, executionTimeMs, pagedResults);
    }

    /**
     * Thực thi trực tiếp đối tượng Lucene Query.
     */
    public List<SearchResultDTO> executeSearch(Query query, int topK) throws Exception {
        if (topK <= 0) {
            return Collections.emptyList();
        }
        TopDocs topDocs = searcher.search(query, topK);
        return buildResults(query, topDocs.scoreDocs);
    }

    /**
     * Chuyển đổi ScoreDoc thành SearchResultDTO và bôi đậm từ khóa bằng Lucene Highlighter.
     */
    private List<SearchResultDTO> buildResults(Query query, ScoreDoc[] scoreDocs) throws Exception {
        List<SearchResultDTO> results = new ArrayList<>(scoreDocs.length);

        SimpleHTMLFormatter formatter = new SimpleHTMLFormatter(HIGHLIGHT_PRE_TAG, HIGHLIGHT_POST_TAG);
        QueryScorer scorer = new QueryScorer(query);
        Highlighter highlighter = new Highlighter(formatter, scorer);
        highlighter.setTextFragmenter(new SimpleSpanFragmenter(scorer, DEFAULT_MAX_FRAGMENT_SIZE));

        for (ScoreDoc scoreDoc : scoreDocs) {
            Document doc = searcher.doc(scoreDoc.doc);
            String title = doc.get(DocumentSchema.FIELD_TITLE);
            String content = doc.get(DocumentSchema.FIELD_CONTENT);
            String url = doc.get(DocumentSchema.FIELD_URL);
            String id = doc.get(DocumentSchema.FIELD_ID);
            String subdomain = doc.get(DocumentSchema.FIELD_SUBDOMAIN);
            String fileType = doc.get(DocumentSchema.FIELD_FILE_TYPE);
            Number crawledDateNum = doc.getField(DocumentSchema.FIELD_CRAWLED_DATE) != null ?
                    doc.getField(DocumentSchema.FIELD_CRAWLED_DATE).numericValue() : 0L;
            long crawledDate = crawledDateNum != null ? crawledDateNum.longValue() : 0L;

            // Highlight cho Content
            String snippet = null;
            if (content != null && !content.isBlank()) {
                try {
                    snippet = highlighter.getBestFragment(analyzer, DocumentSchema.FIELD_CONTENT, content);
                } catch (Exception ignored) {
                }
                if (snippet == null || snippet.isBlank()) {
                    snippet = createFallbackSnippet(content, DEFAULT_MAX_FRAGMENT_SIZE);
                }
            } else {
                snippet = "";
            }

            // Highlight cho Title
            String highlightedTitle = null;
            if (title != null && !title.isBlank()) {
                try {
                    highlightedTitle = highlighter.getBestFragment(analyzer, DocumentSchema.FIELD_TITLE, title);
                } catch (Exception ignored) {
                }
                if (highlightedTitle == null || highlightedTitle.isBlank()) {
                    highlightedTitle = title;
                }
            } else {
                highlightedTitle = "";
            }

            SearchResultDTO dto = SearchResultDTO.builder()
                    .id(id)
                    .url(url)
                    .title(highlightedTitle)
                    .snippet(snippet)
                    .score(scoreDoc.score)
                    .subdomain(subdomain)
                    .crawledDate(crawledDate)
                    .fileType(fileType)
                    .build();

            results.add(dto);
        }

        return results;
    }

    private String createFallbackSnippet(String text, int maxLength) {
        if (text == null) return "";
        String trimmed = text.trim().replaceAll("\\s+", " ");
        if (trimmed.length() <= maxLength) {
            return trimmed;
        }
        return trimmed.substring(0, maxLength) + "...";
    }

    public IndexSearcher getSearcher() {
        return searcher;
    }

    public IndexReader getReader() {
        return reader;
    }

    public Analyzer getAnalyzer() {
        return analyzer;
    }

    public QueryBuilderHelper getQueryBuilderHelper() {
        return queryBuilderHelper;
    }

    @Override
    public void close() throws IOException {
        if (reader != null) {
            reader.close();
        }
        if (ownsDirectory && directory != null) {
            directory.close();
        }
    }
}
