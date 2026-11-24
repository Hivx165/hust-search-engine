package edu.hust.evaluation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.MappingIterator;
import edu.hust.search.analyzer.VietnameseLuceneAnalyzer;
import edu.hust.search.index.DocumentSchema;
import edu.hust.search.query.QueryBuilderHelper;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.index.Term;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Runs the evaluation queries against a read-only Lucene index and computes
 * AP@K/MAP@K for baseline and title-boosted ranking.
 */
public final class BenchmarkRunner {

    public static final int DEFAULT_K = MapCalculator.DEFAULT_K;

    private static final Path DEFAULT_INDEX_PATH = Paths.get("storage", "lucene_index");
    private static final Path DEFAULT_QUERIES_PATH = Paths.get(
            "evaluation-module", "src", "main", "resources", "test_queries.json");
    private static final Path DEFAULT_GROUND_TRUTH_PATH = Paths.get(
            "evaluation-module", "src", "main", "resources", "ground_truth_relevance.csv");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final CsvMapper CSV_MAPPER = new CsvMapper();

    public enum RankingMode {
        BASELINE,
        TITLE_BOOST_SHOULD
    }

    public record QueryDefinition(
            @JsonProperty("query_id") String queryId,
            @JsonProperty("query") String query) {
    }

    public record BenchmarkRun(
            RankingMode rankingMode,
            Map<String, List<String>> retrievedDocIdsByQuery,
            MapCalculator.EvaluationResult evaluation) {
        public BenchmarkRun {
            Map<String, List<String>> resultCopy = new LinkedHashMap<>();
            retrievedDocIdsByQuery.forEach(
                    (queryId, docIds) -> resultCopy.put(queryId, List.copyOf(docIds)));
            retrievedDocIdsByQuery = Collections.unmodifiableMap(resultCopy);
        }
    }

    public static final class GroundTruthRow {
        private String queryId;
        private String docId;
        private Integer relevant;

        public GroundTruthRow() {
        }

        @JsonProperty("query_id")
        public String getQueryId() {
            return queryId;
        }

        @JsonProperty("query_id")
        public void setQueryId(String queryId) {
            this.queryId = queryId;
        }

        @JsonProperty("doc_id")
        public String getDocId() {
            return docId;
        }

        @JsonProperty("doc_id")
        public void setDocId(String docId) {
            this.docId = docId;
        }

        @JsonProperty("relevant")
        public Integer getRelevant() {
            return relevant;
        }

        @JsonProperty("relevant")
        public void setRelevant(Integer relevant) {
            this.relevant = relevant;
        }
    }

    private final Path indexPath;
    private final Path queriesPath;
    private final Path groundTruthPath;
    private final int cutoff;

    public BenchmarkRunner() {
        this(DEFAULT_INDEX_PATH, DEFAULT_QUERIES_PATH, DEFAULT_GROUND_TRUTH_PATH, DEFAULT_K);
    }

    public BenchmarkRunner(Path indexPath, Path queriesPath, Path groundTruthPath) {
        this(indexPath, queriesPath, groundTruthPath, DEFAULT_K);
    }

    public BenchmarkRunner(Path indexPath, Path queriesPath, Path groundTruthPath, int cutoff) {
        if (cutoff <= 0) {
            throw new IllegalArgumentException("Cutoff K must be greater than zero.");
        }
        this.indexPath = Objects.requireNonNull(indexPath, "Index path must not be null");
        this.queriesPath = Objects.requireNonNull(queriesPath, "Queries path must not be null");
        this.groundTruthPath = Objects.requireNonNull(
                groundTruthPath, "Ground-truth path must not be null");
        this.cutoff = cutoff;
    }

    /**
     * Runs all supported ranking modes and returns their retrieved IDs and MAP
     * details. The index is opened read-only; empty or malformed input files
     * produce an IOException with the relevant path.
     */
    public Map<RankingMode, BenchmarkRun> runBoth() throws IOException {
        List<QueryDefinition> queries = loadQueries();
        Map<String, Set<String>> groundTruth = loadGroundTruth(queries);

        if (!Files.isDirectory(indexPath)) {
            throw new IOException("Lucene index directory does not exist: " + indexPath);
        }

        Map<RankingMode, BenchmarkRun> runs = new EnumMap<>(RankingMode.class);
        try (Directory directory = FSDirectory.open(indexPath)) {
            if (!DirectoryReader.indexExists(directory)) {
                throw new IOException("No Lucene index found in: " + indexPath);
            }

            try (DirectoryReader reader = DirectoryReader.open(directory);
                 VietnameseLuceneAnalyzer analyzer = new VietnameseLuceneAnalyzer()) {
                IndexSearcher searcher = new IndexSearcher(reader);
                validateGroundTruthDocIds(searcher, groundTruth);

                for (RankingMode mode : RankingMode.values()) {
                    Map<String, List<String>> retrieved = retrieveResults(
                            searcher, analyzer, queries, mode);
                    MapCalculator.EvaluationResult evaluation = MapCalculator.evaluateAtK(
                            retrieved, groundTruth, cutoff);
                    runs.put(mode, new BenchmarkRun(mode, retrieved, evaluation));
                }
            }
        }

        return Collections.unmodifiableMap(runs);
    }

    private Map<String, List<String>> retrieveResults(
            IndexSearcher searcher,
            VietnameseLuceneAnalyzer analyzer,
            List<QueryDefinition> queries,
            RankingMode mode) throws IOException {
        Map<String, List<String>> retrieved = new LinkedHashMap<>();

        for (QueryDefinition queryDefinition : queries) {
            String shouldKeyword = mode == RankingMode.TITLE_BOOST_SHOULD
                    ? queryDefinition.query()
                    : null;
            Query query;
            try {
                query = QueryBuilderHelper.buildAdvancedQuery(
                        queryDefinition.query(), shouldKeyword, null, analyzer);
            } catch (ParseException e) {
                throw new IOException(
                        "Could not parse query " + queryDefinition.queryId() + ": "
                                + queryDefinition.query(),
                        e);
            }

            TopDocs topDocs = searcher.search(query, cutoff);
            List<String> docIds = new ArrayList<>(topDocs.scoreDocs.length);
            for (ScoreDoc scoreDoc : topDocs.scoreDocs) {
                Document doc = searcher.storedFields().document(
                        scoreDoc.doc, Set.of(DocumentSchema.FIELD_ID));
                String docId = doc.get(DocumentSchema.FIELD_ID);
                if (docId == null || docId.isBlank()) {
                    throw new IOException(
                            "Indexed Lucene document " + scoreDoc.doc
                                    + " has no stored '" + DocumentSchema.FIELD_ID + "' field.");
                }
                docIds.add(docId);
            }
            retrieved.put(queryDefinition.queryId(), docIds);
        }

        return retrieved;
    }

    private void validateGroundTruthDocIds(
            IndexSearcher searcher,
            Map<String, Set<String>> groundTruth) throws IOException {
        Set<String> judgedDocIds = new HashSet<>();
        for (Set<String> queryJudgments : groundTruth.values()) {
            judgedDocIds.addAll(queryJudgments);
        }

        for (String docId : judgedDocIds) {
            Query idQuery = new TermQuery(new Term(DocumentSchema.FIELD_ID, docId));
            if (searcher.count(idQuery) == 0) {
                throw new IOException(
                        "Ground-truth doc_id '" + docId
                                + "' was not found in the Lucene index field '"
                                + DocumentSchema.FIELD_ID + "'.");
            }
        }
    }

    private List<QueryDefinition> loadQueries() throws IOException {
        requireNonEmptyFile(queriesPath, "query JSON");

        List<QueryDefinition> queries;
        try {
            queries = JSON_MAPPER.readValue(
                    queriesPath.toFile(), new TypeReference<List<QueryDefinition>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new IOException(
                    "Invalid query JSON in " + queriesPath
                            + "; expected an array of {\"query_id\":\"Q01\",\"query\":\"...\"}.",
                    e);
        }

        if (queries == null || queries.isEmpty()) {
            throw new IOException("No queries found in " + queriesPath + ".");
        }

        Set<String> queryIds = new HashSet<>();
        for (int i = 0; i < queries.size(); i++) {
            QueryDefinition query = queries.get(i);
            if (query == null
                    || query.queryId() == null || query.queryId().isBlank()
                    || query.query() == null || query.query().isBlank()) {
                throw new IOException(
                        "Query entry " + (i + 1) + " in " + queriesPath
                                + " must have non-empty query_id and query fields.");
            }
            if (!queryIds.add(query.queryId())) {
                throw new IOException(
                        "Duplicate query_id '" + query.queryId() + "' in " + queriesPath + ".");
            }
            queries.set(i, new QueryDefinition(query.queryId().trim(), query.query().trim()));
        }

        return List.copyOf(queries);
    }

    private Map<String, Set<String>> loadGroundTruth(List<QueryDefinition> queries)
            throws IOException {
        requireNonEmptyFile(groundTruthPath, "ground-truth CSV");

        Map<String, Set<String>> groundTruth = new LinkedHashMap<>();
        Set<String> knownQueryIds = new HashSet<>();
        for (QueryDefinition query : queries) {
            groundTruth.put(query.queryId(), new LinkedHashSet<>());
            knownQueryIds.add(query.queryId());
        }

        int rowCount = 0;
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        try (MappingIterator<GroundTruthRow> rows = CSV_MAPPER
                .readerFor(GroundTruthRow.class)
                .with(schema)
                .readValues(groundTruthPath.toFile())) {
            while (rows.hasNextValue()) {
                GroundTruthRow row = rows.nextValue();
                rowCount++;
                if (row.getQueryId() == null || row.getQueryId().isBlank()
                        || row.getDocId() == null || row.getDocId().isBlank()
                        || row.getRelevant() == null) {
                    throw new IOException(
                            "Ground-truth row " + (rowCount + 1) + " in " + groundTruthPath
                                    + " must contain query_id, doc_id, and relevant.");
                }

                String queryId = row.getQueryId().trim();
                String docId = row.getDocId().trim();
                if (!knownQueryIds.contains(queryId)) {
                    throw new IOException(
                            "Ground-truth row references unknown query_id '" + queryId
                                    + "' in " + groundTruthPath + ".");
                }
                if (row.getRelevant() > 0) {
                    groundTruth.get(queryId).add(docId);
                }
            }
        } catch (JsonProcessingException e) {
            throw new IOException("Invalid ground-truth CSV in " + groundTruthPath + ".", e);
        }

        if (rowCount == 0) {
            throw new IOException(
                    "No ground-truth judgments found in " + groundTruthPath
                            + "; expected columns query_id,doc_id,relevant.");
        }

        return groundTruth;
    }

    private static void requireNonEmptyFile(Path path, String description) throws IOException {
        if (!Files.isRegularFile(path)) {
            throw new IOException(description + " file does not exist: " + path);
        }
        if (Files.size(path) == 0) {
            throw new IOException(description + " file is empty: " + path);
        }
    }

    public static void main(String[] args) throws IOException {
        for (BenchmarkRun run : new BenchmarkRunner().runBoth().values()) {
            MapCalculator.EvaluationResult evaluation = run.evaluation();
            System.out.printf(
                    "%s MAP@%d = %.4f (%d evaluated queries)%n",
                    run.rankingMode(),
                    evaluation.cutoff(),
                    evaluation.meanAveragePrecisionAtK(),
                    evaluation.evaluatedQueryCount());
            evaluation.averagePrecisionByQuery().forEach((queryId, ap) ->
                    System.out.printf("  %s AP@%d = %.4f%n", queryId, evaluation.cutoff(), ap));
        }
    }
}