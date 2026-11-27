package edu.hust.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.csv.CsvMapper;
import com.fasterxml.jackson.dataformat.csv.CsvSchema;
import com.fasterxml.jackson.databind.MappingIterator;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Runs the real-index benchmark and exports its metrics, charts, and analysis.
 */
public final class EvaluationReportExporter {

    private static final Path DEFAULT_INDEX_PATH = Paths.get("storage", "lucene_index");
    private static final Path DEFAULT_QUERIES_PATH = Paths.get(
            "evaluation-module", "src", "main", "resources", "test_queries.json");
    private static final Path DEFAULT_GROUND_TRUTH_PATH = Paths.get(
            "evaluation-module", "src", "main", "resources", "ground_truth_relevance.csv");
    private static final Path DEFAULT_REPORTS_PATH = Paths.get("reports");
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();
    private static final CsvMapper CSV_MAPPER = new CsvMapper();

    private EvaluationReportExporter() {
    }

    public record JudgmentSummary(int total, int relevant, int nonRelevant) {
        public JudgmentSummary {
            if (total < 0 || relevant < 0 || nonRelevant < 0
                    || total != relevant + nonRelevant) {
                throw new IllegalArgumentException("Invalid ground-truth judgment counts.");
            }
        }
    }

    public record ReportFiles(
            Path csv,
            Path markdown,
            Path html,
            Path apChart,
            Path mapChart) {
    }

    private record QueryMetric(String queryId, String query, double baselineAp, double boostedAp) {
        double delta() {
            return boostedAp - baselineAp;
        }
    }

    /**
     * Writes all report formats using the supplied benchmark results.
     *
     * @throws IllegalArgumentException if either ranking mode or a query result is missing
     * @throws IOException if a report cannot be written
     */
    public static ReportFiles export(
            Path reportsPath,
            Map<BenchmarkRunner.RankingMode, BenchmarkRunner.BenchmarkRun> runs,
            List<BenchmarkRunner.QueryDefinition> queries,
            JudgmentSummary judgmentSummary) throws IOException {
        Objects.requireNonNull(reportsPath, "Reports path must not be null");
        Objects.requireNonNull(runs, "Benchmark runs must not be null");
        Objects.requireNonNull(queries, "Queries must not be null");
        Objects.requireNonNull(judgmentSummary, "Judgment summary must not be null");
        if (queries.isEmpty()) {
            throw new IllegalArgumentException("At least one query is required to export a report.");
        }

        BenchmarkRunner.BenchmarkRun baseline = requireRun(
                runs, BenchmarkRunner.RankingMode.BASELINE);
        BenchmarkRunner.BenchmarkRun boosted = requireRun(
                runs, BenchmarkRunner.RankingMode.TITLE_BOOST_SHOULD);
        int cutoff = baseline.evaluation().cutoff();
        if (boosted.evaluation().cutoff() != cutoff) {
            throw new IllegalArgumentException("Ranking modes must use the same cutoff.");
        }

        List<QueryMetric> metrics = new ArrayList<>(queries.size());
        for (BenchmarkRunner.QueryDefinition query : queries) {
            Double baselineAp = baseline.evaluation()
                    .averagePrecisionByQuery().get(query.queryId());
            Double boostedAp = boosted.evaluation()
                    .averagePrecisionByQuery().get(query.queryId());
            if (baselineAp == null || boostedAp == null) {
                throw new IllegalArgumentException(
                        "Missing AP result for query " + query.queryId() + ".");
            }
            metrics.add(new QueryMetric(
                    query.queryId(), query.query(), baselineAp, boostedAp));
        }

        Files.createDirectories(reportsPath);
        Path csv = reportsPath.resolve("ap_at_" + cutoff + ".csv");
        Path markdown = reportsPath.resolve("benchmark_report.md");
        Path html = reportsPath.resolve("benchmark_dashboard.html");
        Path apChart = reportsPath.resolve("ap_at_" + cutoff + "_comparison.svg");
        Path mapChart = reportsPath.resolve("map_at_" + cutoff + "_comparison.svg");

        Files.writeString(csv, buildCsv(metrics, cutoff, baseline, boosted), StandardCharsets.UTF_8);
        Files.writeString(
                apChart, buildApChart(metrics, cutoff), StandardCharsets.UTF_8);
        Files.writeString(
                mapChart, buildMapChart(cutoff, baseline, boosted), StandardCharsets.UTF_8);
        Files.writeString(
                markdown,
                buildMarkdown(metrics, cutoff, baseline, boosted, judgmentSummary),
                StandardCharsets.UTF_8);
        Files.writeString(
                html,
                buildHtml(metrics, cutoff, baseline, boosted, judgmentSummary),
                StandardCharsets.UTF_8);

        return new ReportFiles(csv, markdown, html, apChart, mapChart);
    }

    public static void main(String[] args) throws IOException {
        if (args.length > 1) {
            throw new IllegalArgumentException(
                    "Usage: EvaluationReportExporter [reports-directory]");
        }

        Path reportsPath = args.length == 1
                ? Paths.get(args[0])
                : DEFAULT_REPORTS_PATH;
        Map<BenchmarkRunner.RankingMode, BenchmarkRunner.BenchmarkRun> runs =
                new BenchmarkRunner(
                        DEFAULT_INDEX_PATH,
                        DEFAULT_QUERIES_PATH,
                        DEFAULT_GROUND_TRUTH_PATH,
                        BenchmarkRunner.DEFAULT_K)
                        .runBoth();
        List<BenchmarkRunner.QueryDefinition> queries = readQueries(DEFAULT_QUERIES_PATH);
        JudgmentSummary judgments = readJudgmentSummary(DEFAULT_GROUND_TRUTH_PATH);
        ReportFiles files = export(reportsPath, runs, queries, judgments);

        System.out.printf(
                Locale.ROOT,
                "BASELINE MAP@%d = %s%nTITLE_BOOST_SHOULD MAP@%d = %s%n",
                runs.get(BenchmarkRunner.RankingMode.BASELINE).evaluation().cutoff(),
                formatMetric(runs.get(BenchmarkRunner.RankingMode.BASELINE)
                        .evaluation().meanAveragePrecisionAtK()),
                runs.get(BenchmarkRunner.RankingMode.TITLE_BOOST_SHOULD).evaluation().cutoff(),
                formatMetric(runs.get(BenchmarkRunner.RankingMode.TITLE_BOOST_SHOULD)
                        .evaluation().meanAveragePrecisionAtK()));
        System.out.println("CSV report: " + files.csv().toAbsolutePath());
        System.out.println("Markdown report: " + files.markdown().toAbsolutePath());
        System.out.println("HTML dashboard: " + files.html().toAbsolutePath());
        System.out.println("AP chart: " + files.apChart().toAbsolutePath());
        System.out.println("MAP chart: " + files.mapChart().toAbsolutePath());
    }

    private static BenchmarkRunner.BenchmarkRun requireRun(
            Map<BenchmarkRunner.RankingMode, BenchmarkRunner.BenchmarkRun> runs,
            BenchmarkRunner.RankingMode mode) {
        BenchmarkRunner.BenchmarkRun run = runs.get(mode);
        if (run == null || run.rankingMode() != mode || run.evaluation() == null) {
            throw new IllegalArgumentException("Missing valid benchmark result for " + mode + ".");
        }
        return run;
    }

    private static List<BenchmarkRunner.QueryDefinition> readQueries(Path path)
            throws IOException {
        return JSON_MAPPER.readValue(
                path.toFile(),
                new TypeReference<List<BenchmarkRunner.QueryDefinition>>() {
                });
    }

    private static JudgmentSummary readJudgmentSummary(Path path) throws IOException {
        int total = 0;
        int relevant = 0;
        CsvSchema schema = CsvSchema.emptySchema().withHeader();
        try (MappingIterator<BenchmarkRunner.GroundTruthRow> rows = CSV_MAPPER
                .readerFor(BenchmarkRunner.GroundTruthRow.class)
                .with(schema)
                .readValues(path.toFile())) {
            while (rows.hasNextValue()) {
                BenchmarkRunner.GroundTruthRow row = rows.nextValue();
                if (row.getRelevant() == null) {
                    throw new IOException(
                            "Ground-truth row is missing relevance in " + path + ".");
                }
                total++;
                if (row.getRelevant() > 0) {
                    relevant++;
                }
            }
        } catch (JsonProcessingException e) {
            throw new IOException("Invalid ground-truth CSV in " + path + ".", e);
        }
        return new JudgmentSummary(total, relevant, total - relevant);
    }

    private static String buildCsv(
            List<QueryMetric> metrics,
            int cutoff,
            BenchmarkRunner.BenchmarkRun baseline,
            BenchmarkRunner.BenchmarkRun boosted) {
        StringBuilder csv = new StringBuilder()
                .append("query_id,query,baseline_ap_at_").append(cutoff)
                .append(",title_boost_should_ap_at_").append(cutoff).append('\n');
        for (QueryMetric metric : metrics) {
            csv.append(csvField(metric.queryId())).append(',')
                    .append(csvField(metric.query())).append(',')
                    .append(formatMetric(metric.baselineAp())).append(',')
                    .append(formatMetric(metric.boostedAp())).append('\n');
        }
        csv.append(csvField("MAP@" + cutoff)).append(",,")
                .append(formatMetric(baseline.evaluation().meanAveragePrecisionAtK()))
                .append(',')
                .append(formatMetric(boosted.evaluation().meanAveragePrecisionAtK()))
                .append('\n');
        return csv.toString();
    }

    private static String buildMarkdown(
            List<QueryMetric> metrics,
            int cutoff,
            BenchmarkRunner.BenchmarkRun baseline,
            BenchmarkRunner.BenchmarkRun boosted,
            JudgmentSummary judgments) {
        double baselineMap = baseline.evaluation().meanAveragePrecisionAtK();
        double boostedMap = boosted.evaluation().meanAveragePrecisionAtK();
        double mapDelta = boostedMap - baselineMap;
        int[] comparison = countOutcomes(metrics);
        StringBuilder report = new StringBuilder()
                .append("# HUST Search Evaluation Report\n\n")
                .append("- Generated: ")
                .append(OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .append("\n- Index: `storage/lucene_index` (opened read-only)\n")
                .append("- Cutoff: Top-").append(cutoff).append("\n")
                .append("- Queries: ").append(metrics.size()).append("\n")
                .append("- Ground-truth judgments: ").append(judgments.total())
                .append(" (").append(judgments.relevant()).append(" relevant, ")
                .append(judgments.nonRelevant()).append(" non-relevant)\n\n")
                .append("## Aggregate result\n\n")
                .append("| Ranking mode | MAP@").append(cutoff).append(" |\n")
                .append("| --- | ---: |\n")
                .append("| BASELINE | ").append(formatMetric(baselineMap)).append(" |\n")
                .append("| TITLE_BOOST_SHOULD | ").append(formatMetric(boostedMap)).append(" |\n\n")
                .append("The boosted configuration changed MAP@").append(cutoff)
                .append(" by **").append(formatSignedMetric(mapDelta))
                .append("** relative to BASELINE. Per-query AP improved for ")
                .append(comparison[0]).append(" queries, was unchanged for ")
                .append(comparison[1]).append(", and decreased for ")
                .append(comparison[2]).append(".\n\n")
                .append("## AP by query\n\n")
                .append("| Query ID | Query | BASELINE AP@").append(cutoff)
                .append(" | TITLE_BOOST_SHOULD AP@").append(cutoff).append(" |\n")
                .append("| --- | --- | ---: | ---: |\n");

        for (QueryMetric metric : metrics) {
            report.append("| ").append(metric.queryId()).append(" | ")
                    .append(markdownCell(metric.query())).append(" | ")
                    .append(formatMetric(metric.baselineAp())).append(" | ")
                    .append(formatMetric(metric.boostedAp())).append(" |\n");
        }

        report.append("| **MAP@").append(cutoff).append("** | **Aggregate** | **")
                .append(formatMetric(baselineMap)).append("** | **")
                .append(formatMetric(boostedMap)).append("** |\n\n")
                .append("## Interpretation and limitations\n\n")
                .append("- AP counts only explicitly judged relevant IDs; retrieved but unjudged ")
                .append("documents do not contribute to the relevant numerator. An unjudged ")
                .append("document is not evidence of true irrelevance, so incomplete qrels can ")
                .append("bias the measured score.\n")
                .append("- The ").append(judgments.total())
                .append(" judgments cover a sample of the retrieved documents, not every document ")
                .append("in the index. MAP should therefore be read as a result on this labeled ")
                .append("25-query sample, not as a complete relevance assessment of the corpus.\n")
                .append("- BASELINE omits the optional SHOULD clause; TITLE_BOOST_SHOULD uses ")
                .append("the benchmark's title-boosted SHOULD configuration. Both use the same ")
                .append("queries, index, qrels, and Top-").append(cutoff).append(" cutoff.\n")
                .append("- The AP and MAP charts and machine-readable values are in ")
                .append("`ap_at_").append(cutoff).append("_comparison.svg`, `map_at_")
                .append(cutoff).append("_comparison.svg`, and `ap_at_").append(cutoff)
                .append(".csv`.\n");
        return report.toString();
    }

    private static String buildHtml(
            List<QueryMetric> metrics,
            int cutoff,
            BenchmarkRunner.BenchmarkRun baseline,
            BenchmarkRunner.BenchmarkRun boosted,
            JudgmentSummary judgments) {
        StringBuilder html = new StringBuilder()
                .append("<!doctype html>\n<html lang=\"vi\"><head><meta charset=\"utf-8\">")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
                .append("<title>HUST Search Evaluation</title>")
                .append("<style>body{font:16px system-ui,sans-serif;max-width:1200px;margin:2rem auto;padding:0 1rem;color:#172033}")
                .append("table{border-collapse:collapse;width:100%;margin:1rem 0 2rem}th,td{border:1px solid #d5dbe5;padding:.55rem;text-align:left}")
                .append("th{background:#f1f5f9}td.num,th.num{text-align:right}.charts{display:grid;grid-template-columns:1fr;gap:1rem}")
                .append("img{max-width:100%;height:auto;border:1px solid #e2e8f0}code{background:#f1f5f9;padding:.1rem .25rem}</style>")
                .append("</head><body><h1>HUST Search Evaluation Report</h1>")
                .append("<p>Top-").append(cutoff).append(" on <code>storage/lucene_index</code>; ")
                .append(metrics.size()).append(" queries and ").append(judgments.total())
                .append(" judgments (").append(judgments.relevant()).append(" relevant, ")
                .append(judgments.nonRelevant()).append(" non-relevant).</p>")
                .append("<h2>MAP@").append(cutoff).append("</h2><table><thead><tr>")
                .append("<th>Ranking mode</th><th class=\"num\">MAP@").append(cutoff)
                .append("</th></tr></thead><tbody><tr><td>BASELINE</td><td class=\"num\">")
                .append(formatMetric(baseline.evaluation().meanAveragePrecisionAtK()))
                .append("</td></tr><tr><td>TITLE_BOOST_SHOULD</td><td class=\"num\">")
                .append(formatMetric(boosted.evaluation().meanAveragePrecisionAtK()))
                .append("</td></tr></tbody></table>")
                .append("<section class=\"charts\"><img src=\"ap_at_").append(cutoff)
                .append("_comparison.svg\" alt=\"AP per query comparison\"><img src=\"map_at_")
                .append(cutoff).append("_comparison.svg\" alt=\"Aggregate MAP comparison\"></section>")
                .append("<h2>AP@").append(cutoff).append(" by query</h2>")
                .append("<table><thead><tr><th>ID</th><th>Query</th><th class=\"num\">BASELINE</th>")
                .append("<th class=\"num\">TITLE_BOOST_SHOULD</th></tr></thead><tbody>");
        for (QueryMetric metric : metrics) {
            html.append("<tr><td>").append(escapeHtml(metric.queryId()))
                    .append("</td><td>").append(escapeHtml(metric.query()))
                    .append("</td><td class=\"num\">").append(formatMetric(metric.baselineAp()))
                    .append("</td><td class=\"num\">").append(formatMetric(metric.boostedAp()))
                    .append("</td></tr>");
        }
        html.append("</tbody></table><p><a href=\"benchmark_report.md\">Detailed analysis</a> · ")
                .append("<a href=\"ap_at_").append(cutoff).append(".csv\">CSV data</a></p>")
                .append("<p>Scores are limited to the supplied labeled query-document sample; ")
                .append("see the detailed report for methodology and limitations.</p>")
                .append("</body></html>\n");
        return html.toString();
    }

    private static String buildApChart(List<QueryMetric> metrics, int cutoff) {
        int width = 1400;
        int height = 500;
        int left = 64;
        int right = 28;
        int top = 60;
        int bottom = 105;
        int plotHeight = height - top - bottom;
        double plotWidth = width - left - right;
        double groupWidth = plotWidth / metrics.size();
        double barWidth = Math.min(15.0, groupWidth * 0.28);
        StringBuilder svg = svgStart(width, height)
                .append("<title>AP@").append(cutoff)
                .append(" comparison by query</title><desc>Grouped bars compare baseline and title-boosted AP.</desc>")
                .append("<text x=\"").append(width / 2)
                .append("\" y=\"28\" text-anchor=\"middle\" font-size=\"18\" font-weight=\"bold\">AP@")
                .append(cutoff).append(" by query</text>");

        appendLegend(svg, width - right - 270, 42);
        appendYAxis(svg, left, top, width - right, plotHeight);

        for (int i = 0; i < metrics.size(); i++) {
            QueryMetric metric = metrics.get(i);
            double center = left + (i + 0.5) * groupWidth;
            appendBar(svg, center - barWidth - 1, top, plotHeight, barWidth,
                    metric.baselineAp(), "#2563eb", metric.queryId() + " BASELINE");
            appendBar(svg, center + 1, top, plotHeight, barWidth,
                    metric.boostedAp(), "#f97316", metric.queryId() + " TITLE_BOOST_SHOULD");
            svg.append("<text x=\"").append(formatNumber(center))
                    .append("\" y=\"").append(height - bottom + 22)
                    .append("\" text-anchor=\"end\" font-size=\"11\" transform=\"rotate(-45 ")
                    .append(formatNumber(center)).append(' ')
                    .append(height - bottom + 22).append(")\">")
                    .append(escapeXml(metric.queryId())).append("</text>");
        }
        return svg.append("</svg>\n").toString();
    }

    private static String buildMapChart(
            int cutoff,
            BenchmarkRunner.BenchmarkRun baseline,
            BenchmarkRunner.BenchmarkRun boosted) {
        int width = 640;
        int height = 380;
        int left = 72;
        int right = 40;
        int top = 58;
        int bottom = 70;
        int plotHeight = height - top - bottom;
        StringBuilder svg = svgStart(width, height)
                .append("<title>MAP@").append(cutoff)
                .append(" comparison</title><desc>Aggregate MAP scores for the two ranking modes.</desc>")
                .append("<text x=\"").append(width / 2)
                .append("\" y=\"28\" text-anchor=\"middle\" font-size=\"18\" font-weight=\"bold\">MAP@")
                .append(cutoff).append(" comparison</text>");
        appendYAxis(svg, left, top, width - right, plotHeight);

        double[] values = {
                baseline.evaluation().meanAveragePrecisionAtK(),
                boosted.evaluation().meanAveragePrecisionAtK()
        };
        String[] labels = {"BASELINE", "TITLE_BOOST_SHOULD"};
        String[] colors = {"#2563eb", "#f97316"};
        double[] centers = {width * 0.36, width * 0.70};
        for (int i = 0; i < values.length; i++) {
            appendBar(svg, centers[i] - 42, top, plotHeight, 84,
                    values[i], colors[i], labels[i]);
            svg.append("<text x=\"").append(formatNumber(centers[i]))
                    .append("\" y=\"").append(height - bottom + 28)
                    .append("\" text-anchor=\"middle\" font-size=\"13\">")
                    .append(labels[i]).append("</text>")
                    .append("<text x=\"").append(formatNumber(centers[i]))
                    .append("\" y=\"").append(formatNumber(
                            top + plotHeight - chartValue(values[i]) * plotHeight - 8))
                    .append("\" text-anchor=\"middle\" font-size=\"14\" font-weight=\"bold\">")
                    .append(formatMetric(values[i])).append("</text>");
        }
        return svg.append("</svg>\n").toString();
    }

    private static StringBuilder svgStart(int width, int height) {
        return new StringBuilder()
                .append("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"")
                .append(width).append("\" height=\"").append(height)
                .append("\" viewBox=\"0 0 ").append(width).append(' ').append(height)
                .append("\" role=\"img\" style=\"font-family:Arial,sans-serif;background:#fff\">");
    }

    private static void appendYAxis(
            StringBuilder svg, int left, int top, int right, int plotHeight) {
        for (int tick = 0; tick <= 5; tick++) {
            double value = tick / 5.0;
            double y = top + plotHeight - value * plotHeight;
            svg.append("<line x1=\"").append(left).append("\" y1=\"")
                    .append(formatNumber(y)).append("\" x2=\"").append(right)
                    .append("\" y2=\"").append(formatNumber(y))
                    .append("\" stroke=\"#dbe2ea\" stroke-width=\"1\"/>")
                    .append("<text x=\"").append(left - 10).append("\" y=\"")
                    .append(formatNumber(y + 4))
                    .append("\" text-anchor=\"end\" font-size=\"11\">")
                    .append(String.format(Locale.ROOT, "%.1f", value)).append("</text>");
        }
    }

    private static void appendLegend(StringBuilder svg, int x, int y) {
        svg.append("<rect x=\"").append(x).append("\" y=\"").append(y)
                .append("\" width=\"12\" height=\"12\" fill=\"#2563eb\"/>")
                .append("<text x=\"").append(x + 18).append("\" y=\"").append(y + 11)
                .append("\" font-size=\"12\">BASELINE</text>")
                .append("<rect x=\"").append(x + 105).append("\" y=\"").append(y)
                .append("\" width=\"12\" height=\"12\" fill=\"#f97316\"/>")
                .append("<text x=\"").append(x + 123).append("\" y=\"").append(y + 11)
                .append("\" font-size=\"12\">TITLE_BOOST_SHOULD</text>");
    }

    private static void appendBar(
            StringBuilder svg,
            double x,
            int top,
            int plotHeight,
            double width,
            double value,
            String color,
            String label) {
        double chartValue = chartValue(value);
        double barHeight = chartValue * plotHeight;
        svg.append("<rect x=\"").append(formatNumber(x))
                .append("\" y=\"").append(formatNumber(top + plotHeight - barHeight))
                .append("\" width=\"").append(formatNumber(width))
                .append("\" height=\"").append(formatNumber(barHeight))
                .append("\" fill=\"").append(color).append("\"><title>")
                .append(escapeXml(label)).append(": ").append(formatMetric(value))
                .append("</title></rect>");
    }

    private static int[] countOutcomes(List<QueryMetric> metrics) {
        int[] outcomes = new int[3];
        for (QueryMetric metric : metrics) {
            double delta = metric.delta();
            if (!Double.isFinite(delta) || Math.abs(delta) < 1e-12) {
                outcomes[1]++;
            } else if (delta > 0) {
                outcomes[0]++;
            } else {
                outcomes[2]++;
            }
        }
        return outcomes;
    }

    private static String csvField(String value) {
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static String markdownCell(String value) {
        return value.replace("|", "\\|").replace("\r", " ").replace("\n", " ");
    }

    private static String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    private static String escapeXml(String value) {
        return escapeHtml(value);
    }

    private static double chartValue(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(1.0, value)) : 0.0;
    }

    private static String formatMetric(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%.4f", value)
                : "N/A";
    }

    private static String formatSignedMetric(double value) {
        return Double.isFinite(value)
                ? String.format(Locale.ROOT, "%+.4f", value)
                : "N/A";
    }

    private static String formatNumber(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}