package edu.hust.evaluation;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EvaluationReportExporterTest {

    @TempDir
    Path tempDir;

    @Test
    void exportsMetricsAnalysisAndChartsFromBenchmarkResults() throws IOException {
        MapCalculator.EvaluationResult baselineEvaluation =
                new MapCalculator.EvaluationResult(Map.of("Q01", 0.5), 0.5, 1, 50);
        MapCalculator.EvaluationResult boostedEvaluation =
                new MapCalculator.EvaluationResult(Map.of("Q01", 0.75), 0.75, 1, 50);
        Map<BenchmarkRunner.RankingMode, BenchmarkRunner.BenchmarkRun> runs = Map.of(
                BenchmarkRunner.RankingMode.BASELINE,
                new BenchmarkRunner.BenchmarkRun(
                        BenchmarkRunner.RankingMode.BASELINE,
                        Map.of("Q01", List.of("doc-1")),
                        baselineEvaluation),
                BenchmarkRunner.RankingMode.TITLE_BOOST_SHOULD,
                new BenchmarkRunner.BenchmarkRun(
                        BenchmarkRunner.RankingMode.TITLE_BOOST_SHOULD,
                        Map.of("Q01", List.of("doc-1")),
                        boostedEvaluation));

        EvaluationReportExporter.ReportFiles files = EvaluationReportExporter.export(
                tempDir.resolve("reports"),
                runs,
                List.of(new BenchmarkRunner.QueryDefinition("Q01", "học bổng")),
                new EvaluationReportExporter.JudgmentSummary(3, 2, 1));

        assertTrue(Files.isRegularFile(files.csv()));
        assertTrue(Files.readString(files.csv()).contains("0.5000,0.7500"));
        assertTrue(Files.isRegularFile(files.markdown()));
        assertTrue(Files.readString(files.markdown()).contains("changed MAP@50 by **+0.2500**"));
        assertTrue(Files.isRegularFile(files.html()));
        assertTrue(Files.readString(files.html()).contains("học bổng"));
        assertTrue(Files.isRegularFile(files.apChart()));
        assertTrue(Files.readString(files.apChart()).contains("Q01 BASELINE"));
        assertTrue(Files.isRegularFile(files.mapChart()));
        assertTrue(Files.readString(files.mapChart()).contains("TITLE_BOOST_SHOULD"));
    }
}
