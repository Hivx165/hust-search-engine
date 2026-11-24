package edu.hust.evaluation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class MapCalculatorTest {

    @Test
    void perfectRankingHasMapOfOne() {
        MapCalculator.EvaluationResult result = MapCalculator.evaluateAtK(
                Map.of("q1", List.of("d1", "d2")),
                Map.of("q1", Set.of("d1", "d2")),
                50);

        assertEquals(1.0, result.averagePrecisionByQuery().get("q1"), 1e-12);
        assertEquals(1.0, result.meanAveragePrecisionAtK(), 1e-12);
        assertEquals(1, result.evaluatedQueryCount());
    }

    @Test
    void interleavedRankingUsesPrecisionAtRelevantRanks() {
        double averagePrecision = MapCalculator.calculateAveragePrecisionAtK(
                List.of("relevant-1", "irrelevant", "relevant-2"),
                Set.of("relevant-1", "relevant-2"),
                3);

        assertEquals(5.0 / 6.0, averagePrecision, 1e-12);
    }

    @Test
    void noRetrievedRelevantDocumentsHasZeroAp() {
        double averagePrecision = MapCalculator.calculateAveragePrecisionAtK(
                List.of("other-1", "other-2"),
                Set.of("relevant"),
                50);

        assertEquals(0.0, averagePrecision);
    }

    @Test
    void emptyOrNullResultsHaveZeroApWhenGroundTruthHasRelevantDocuments() {
        assertEquals(0.0, MapCalculator.calculateAveragePrecisionAtK(
                List.of(), Set.of("relevant"), 50));
        assertEquals(0.0, MapCalculator.calculateAveragePrecisionAtK(
                null, Set.of("relevant"), 50));
    }

    @Test
    void queryWithoutGroundTruthIsReportedAsNaNAndSkippedFromMap() {
        MapCalculator.EvaluationResult result = MapCalculator.evaluateAtK(
                Map.of("q-with-relevant", List.of("d1")),
                Map.of(
                        "q-with-relevant", Set.of("d1"),
                        "q-without-ground-truth", Set.of()),
                50);

        assertEquals(1.0, result.averagePrecisionByQuery().get("q-with-relevant"), 1e-12);
        assertTrue(Double.isNaN(
                result.averagePrecisionByQuery().get("q-without-ground-truth")));
        assertEquals(1.0, result.meanAveragePrecisionAtK(), 1e-12);
        assertEquals(1, result.evaluatedQueryCount());
    }

    @Test
    void gradedRelevanceAndDuplicateResultsAreHandled() {
        MapCalculator.EvaluationResult result = MapCalculator.evaluateRelevanceAtK(
                Map.of("q1", List.of("d1", "d1", "other", "d2")),
                Map.of("q1", Map.of("d1", 0, "d2", 2)),
                3);

        assertEquals(1.0 / 3.0, result.averagePrecisionByQuery().get("q1"), 1e-12);
    }

    @Test
    void rejectsNonPositiveCutoff() {
        assertThrows(IllegalArgumentException.class, () ->
                MapCalculator.calculateAveragePrecisionAtK(
                        List.of("d1"), Set.of("d1"), 0));
    }

    @Test
    void allQueriesWithoutPositiveGroundTruthProduceNaNMap() {
        MapCalculator.EvaluationResult result = MapCalculator.evaluateAtK(
                Map.of(),
                Map.of("q1", Set.of()),
                50);

        assertTrue(Double.isNaN(result.averagePrecisionByQuery().get("q1")));
        assertTrue(Double.isNaN(result.meanAveragePrecisionAtK()));
        assertEquals(0, result.evaluatedQueryCount());
    }
}
