package edu.hust.evaluation;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Calculates Average Precision and Mean Average Precision for ranked results.
 */
public final class MapCalculator {

    public static final int DEFAULT_K = 50;

    private MapCalculator() {
    }

    /**
     * Detailed per-query and aggregate MAP@K results. Queries with no positive
     * relevance judgment have an AP value of {@link Double#NaN} and are excluded
     * from the aggregate denominator.
     */
    public record EvaluationResult(
            Map<String, Double> averagePrecisionByQuery,
            double meanAveragePrecisionAtK,
            int evaluatedQueryCount,
            int cutoff) {
        public EvaluationResult {
            averagePrecisionByQuery = Collections.unmodifiableMap(
                    new LinkedHashMap<>(averagePrecisionByQuery));
        }
    }

    /**
     * Calculates AP using the default cutoff of 50.
     *
     * @return AP, or {@link Double#NaN} when the query has no relevant documents
     */
    public static double calculateAveragePrecision(
            List<String> retrievedDocIds,
            Set<String> groundTruthRelevantDocIds) {
        return calculateAveragePrecisionAtK(retrievedDocIds, groundTruthRelevantDocIds, DEFAULT_K);
    }

    /**
     * Calculates AP@K, normalizing by all relevant documents in the ground truth.
     * Duplicate and null result IDs are ignored; null result lists are treated as
     * empty. A query with no relevant ground-truth IDs has undefined AP (NaN).
     *
     * @throws IllegalArgumentException if {@code k} is not positive
     */
    public static double calculateAveragePrecisionAtK(
            List<String> retrievedDocIds,
            Set<String> groundTruthRelevantDocIds,
            int k) {
        validateCutoff(k);

        int relevantCount = countRelevant(groundTruthRelevantDocIds);
        if (relevantCount == 0) {
            return Double.NaN;
        }
        if (retrievedDocIds == null || retrievedDocIds.isEmpty()) {
            return 0.0;
        }

        Set<String> seenDocIds = new HashSet<>();
        int rank = 0;
        int relevantRetrieved = 0;
        double precisionSum = 0.0;

        for (String docId : retrievedDocIds) {
            if (docId == null || !seenDocIds.add(docId)) {
                continue;
            }

            rank++;
            if (rank > k) {
                break;
            }

            if (groundTruthRelevantDocIds.contains(docId)) {
                relevantRetrieved++;
                precisionSum += (double) relevantRetrieved / rank;
            }
        }

        return precisionSum / relevantCount;
    }

    /**
     * Calculates detailed MAP using the default cutoff of 50.
     */
    public static EvaluationResult evaluateAtK(
            Map<String, ? extends List<String>> allRetrievedResults,
            Map<String, ? extends Set<String>> allGroundTruths) {
        return evaluateAtK(allRetrievedResults, allGroundTruths, DEFAULT_K);
    }

    /**
     * Calculates per-query AP@K and MAP@K. Queries present in ground truth but
     * without any relevant documents are reported with NaN AP and excluded from
     * the MAP denominator. Missing result lists count as empty ranked results.
     *
     * @throws IllegalArgumentException if {@code k} is not positive
     */
    public static EvaluationResult evaluateAtK(
            Map<String, ? extends List<String>> allRetrievedResults,
            Map<String, ? extends Set<String>> allGroundTruths,
            int k) {
        validateCutoff(k);

        Map<String, Double> averagePrecisionByQuery = new LinkedHashMap<>();
        double averagePrecisionSum = 0.0;
        int evaluatedQueryCount = 0;

        if (allGroundTruths != null) {
            for (Map.Entry<String, ? extends Set<String>> query : allGroundTruths.entrySet()) {
                List<String> retrieved = allRetrievedResults == null
                        ? null
                        : allRetrievedResults.get(query.getKey());
                double averagePrecision = calculateAveragePrecisionAtK(
                        retrieved, query.getValue(), k);
                averagePrecisionByQuery.put(query.getKey(), averagePrecision);

                if (!Double.isNaN(averagePrecision)) {
                    averagePrecisionSum += averagePrecision;
                    evaluatedQueryCount++;
                }
            }
        }

        double meanAveragePrecision = evaluatedQueryCount == 0
                ? Double.NaN
                : averagePrecisionSum / evaluatedQueryCount;

        return new EvaluationResult(
                averagePrecisionByQuery,
                meanAveragePrecision,
                evaluatedQueryCount,
                k);
    }

    /**
     * Calculates detailed MAP@K from graded relevance judgments. Any relevance
     * value greater than zero is treated as relevant.
     */
    public static EvaluationResult evaluateRelevanceAtK(
            Map<String, ? extends List<String>> allRetrievedResults,
            Map<String, ? extends Map<String, ? extends Number>> relevanceByQuery,
            int k) {
        validateCutoff(k);

        Map<String, Set<String>> binaryGroundTruths = new LinkedHashMap<>();
        if (relevanceByQuery != null) {
            for (Map.Entry<String, ? extends Map<String, ? extends Number>> query
                    : relevanceByQuery.entrySet()) {
                Set<String> relevantDocIds = new HashSet<>();
                Map<String, ? extends Number> judgments = query.getValue();
                if (judgments != null) {
                    for (Map.Entry<String, ? extends Number> judgment : judgments.entrySet()) {
                        Number relevance = judgment.getValue();
                        if (judgment.getKey() != null
                                && relevance != null
                                && relevance.doubleValue() > 0) {
                            relevantDocIds.add(judgment.getKey());
                        }
                    }
                }
                binaryGroundTruths.put(query.getKey(), relevantDocIds);
            }
        }

        return evaluateAtK(allRetrievedResults, binaryGroundTruths, k);
    }

    /**
     * Calculates MAP using the default cutoff of 50.
     */
    public static double calculateMAP(
            Map<String, List<String>> allRetrievedResults,
            Map<String, Set<String>> allGroundTruths) {
        return calculateMAP(allRetrievedResults, allGroundTruths, DEFAULT_K);
    }

    /**
     * Calculates MAP@K. Queries without any relevant ground-truth documents are
     * excluded from the denominator; if no query is evaluable, returns NaN.
     *
     * @throws IllegalArgumentException if {@code k} is not positive
     */
    public static double calculateMAP(
            Map<String, List<String>> allRetrievedResults,
            Map<String, Set<String>> allGroundTruths,
            int k) {
        return evaluateAtK(allRetrievedResults, allGroundTruths, k).meanAveragePrecisionAtK();
    }

    private static int countRelevant(Set<String> relevantDocIds) {
        if (relevantDocIds == null || relevantDocIds.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (String docId : relevantDocIds) {
            if (docId != null) {
                count++;
            }
        }
        return count;
    }

    private static void validateCutoff(int k) {
        if (k <= 0) {
            throw new IllegalArgumentException("Cutoff K must be greater than zero.");
        }
    }
}
