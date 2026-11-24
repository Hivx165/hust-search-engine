package edu.hust.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.hust.search.index.LuceneIndexManager;
import edu.hust.search.model.DocumentModel;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BenchmarkRunnerTest {

    @TempDir
    Path tempDir;

    @Test
    void loadsInputsSearchesLuceneAndEvaluatesBothRankingModes() throws IOException {
        Path indexPath = createIndex();
        Path queriesPath = tempDir.resolve("test_queries.json");
        Path groundTruthPath = tempDir.resolve("ground_truth_relevance.csv");
        Files.writeString(
                queriesPath,
                "[{\"query_id\":\"Q01\",\"query\":\"alpha\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(
                groundTruthPath,
                "query_id,doc_id,relevant\nQ01,title-match,1\nQ01,body-match,0\n",
                StandardCharsets.UTF_8);

        Map<BenchmarkRunner.RankingMode, BenchmarkRunner.BenchmarkRun> runs =
                new BenchmarkRunner(indexPath, queriesPath, groundTruthPath).runBoth();

        BenchmarkRunner.BenchmarkRun baseline =
                runs.get(BenchmarkRunner.RankingMode.BASELINE);
        BenchmarkRunner.BenchmarkRun boosted =
                runs.get(BenchmarkRunner.RankingMode.TITLE_BOOST_SHOULD);

        assertEquals(2, runs.size());
        assertEquals(50, boosted.evaluation().cutoff());
        assertEquals(1, boosted.evaluation().evaluatedQueryCount());
        assertEquals(1.0, boosted.evaluation().meanAveragePrecisionAtK(), 1e-12);
        assertEquals("title-match",
                boosted.retrievedDocIdsByQuery().get("Q01").get(0));
        assertTrue(
                boosted.evaluation().meanAveragePrecisionAtK()
                        > baseline.evaluation().meanAveragePrecisionAtK());
    }

    @Test
    void reportsEmptyQueryFileClearly() throws IOException {
        Path queriesPath = tempDir.resolve("empty-queries.json");
        Path groundTruthPath = tempDir.resolve("ground_truth_relevance.csv");
        Files.writeString(queriesPath, "", StandardCharsets.UTF_8);
        Files.writeString(
                groundTruthPath,
                "query_id,doc_id,relevant\nQ01,d1,1\n",
                StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () ->
                new BenchmarkRunner(tempDir.resolve("unused-index"), queriesPath, groundTruthPath)
                        .runBoth());

        assertTrue(error.getMessage().contains("query JSON file is empty"));
    }

    @Test
    void reportsGroundTruthHeaderWithoutRowsClearly() throws IOException {
        Path queriesPath = tempDir.resolve("test_queries.json");
        Path groundTruthPath = tempDir.resolve("empty-ground-truth.csv");
        Files.writeString(
                queriesPath,
                "[{\"query_id\":\"Q01\",\"query\":\"alpha\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(
                groundTruthPath,
                "query_id,doc_id,relevant\n",
                StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () ->
                new BenchmarkRunner(tempDir.resolve("unused-index"), queriesPath, groundTruthPath)
                        .runBoth());

        assertTrue(error.getMessage().contains("No ground-truth judgments found"));
    }

    @Test
    void rejectsGroundTruthDocIdsMissingFromIndex() throws IOException {
        Path indexPath = createIndex();
        Path queriesPath = tempDir.resolve("test_queries.json");
        Path groundTruthPath = tempDir.resolve("ground_truth_relevance.csv");
        Files.writeString(
                queriesPath,
                "[{\"query_id\":\"Q01\",\"query\":\"alpha\"}]",
                StandardCharsets.UTF_8);
        Files.writeString(
                groundTruthPath,
                "query_id,doc_id,relevant\nQ01,missing-doc,1\n",
                StandardCharsets.UTF_8);

        IOException error = assertThrows(IOException.class, () ->
                new BenchmarkRunner(indexPath, queriesPath, groundTruthPath).runBoth());

        assertTrue(error.getMessage().contains("missing-doc"));
        assertTrue(error.getMessage().contains("was not found in the Lucene index"));
    }

    private Path createIndex() throws IOException {
        Path indexPath = tempDir.resolve("lucene-index");
        try (LuceneIndexManager indexManager = new LuceneIndexManager(indexPath)) {
            indexManager.indexBatch(List.of(
                    document("body-match", "", "alpha"),
                    document("title-match", "alpha", ""),
                    document("irrelevant", "unrelated", "unrelated")));
        }
        return indexPath;
    }

    private static DocumentModel document(String id, String title, String content) {
        return new DocumentModel(
                id,
                "https://example.test/" + id,
                title,
                "example.test",
                content,
                "HTML",
                "2026-11-24T00:00:00Z",
                "");
    }
}
