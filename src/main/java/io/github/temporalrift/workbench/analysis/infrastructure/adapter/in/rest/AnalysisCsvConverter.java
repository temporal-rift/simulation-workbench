package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.rest;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpOutputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.AbstractHttpMessageConverter;
import org.springframework.http.converter.HttpMessageNotReadableException;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Comparison;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.MetricDimensions;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Report;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReportCaseCounts;

/**
 * Writes the same report and comparison representations the JSON responses carry as RFC 4180 CSV: a header row,
 * then one row per cohort metric, each repeating its full attribution. An empty field is a null value.
 */
public class AnalysisCsvConverter extends AbstractHttpMessageConverter<Object> {

    public static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private static final String LINE = "\r\n";
    private static final List<String> ATTRIBUTION = List.of(
            "metric",
            "statistic",
            "faction",
            "cardType",
            "cardGrade",
            "specialAction",
            "paradoxType",
            "endReason",
            "quantile",
            "unit");
    private static final List<String> REPORT_COLUMNS = concat(
            List.of(
                    "formatVersion",
                    "runId",
                    "manifestDigest",
                    "analysisVersion",
                    "analysisSeed",
                    "complete",
                    "variantLabel",
                    "policyId",
                    "policyVersion",
                    "playerCount",
                    "factionSet",
                    "seatIndex",
                    "eligibleGames",
                    "independentBlocks",
                    "requested",
                    "pending",
                    "running",
                    "succeeded",
                    "failed",
                    "cancelled"),
            ATTRIBUTION,
            List.of("value", "numerator", "denominator", "status", "intervalLower", "intervalUpper", "unknownCount"));
    private static final List<String> COMPARISON_COLUMNS = concat(
            List.of(
                    "formatVersion",
                    "comparisonId",
                    "baselineRunId",
                    "baselineVariantLabel",
                    "candidateRunId",
                    "candidateVariantLabel",
                    "analysisVersion",
                    "analysisSeed",
                    "complete",
                    "policyId",
                    "policyVersion",
                    "playerCount",
                    "factionSet",
                    "seatIndex",
                    "matchedBlocks",
                    "excludedBlocks"),
            ATTRIBUTION,
            List.of("baselineValue", "candidateValue", "difference", "status", "intervalLower", "intervalUpper"));

    public AnalysisCsvConverter() {
        super(TEXT_CSV);
    }

    @Override
    protected boolean supports(Class<?> type) {
        return Report.class.equals(type) || Comparison.class.equals(type);
    }

    @Override
    protected boolean canRead(MediaType mediaType) {
        return false;
    }

    @Override
    protected Object readInternal(Class<?> type, HttpInputMessage input) {
        throw new HttpMessageNotReadableException("CSV is an export format only", input);
    }

    @Override
    protected void writeInternal(Object body, HttpOutputMessage output) throws IOException {
        Writer writer = new OutputStreamWriter(output.getBody(), StandardCharsets.UTF_8);
        switch (body) {
            case Report report -> writeReport(report, writer);
            case Comparison comparison -> writeComparison(comparison, writer);
            default -> throw new IllegalArgumentException("Not a CSV export: " + body.getClass());
        }
        writer.flush();
    }

    private static void writeReport(Report report, Writer writer) throws IOException {
        row(writer, REPORT_COLUMNS);
        for (var cohort : report.getCohorts()) {
            for (var metric : cohort.getMetrics()) {
                var fields = new ArrayList<Object>(List.of(
                        report.getFormatVersion().getValue(),
                        report.getRunId(),
                        report.getManifestDigest(),
                        report.getAnalysisVersion(),
                        report.getAnalysisSeed(),
                        report.getComplete(),
                        cohort.getVariantLabel(),
                        cohort.getPolicyId(),
                        cohort.getPolicyVersion(),
                        cohort.getPlayerCount().getValue()));
                fields.add(factionSet(cohort.getFactionSet()));
                fields.add(cohort.getSeatIndex());
                fields.add(cohort.getEligibleGames());
                fields.add(cohort.getIndependentBlocks());
                fields.addAll(counts(cohort.getCaseCounts()));
                fields.add(metric.getName());
                fields.add(metric.getStatistic());
                fields.addAll(dimensions(metric.getDimensions()));
                fields.add(metric.getUnit());
                fields.add(metric.getValue());
                fields.add(metric.getNumerator());
                fields.add(metric.getDenominator());
                fields.add(metric.getStatus());
                fields.add(
                        metric.getInterval() == null
                                ? null
                                : metric.getInterval().getLower());
                fields.add(
                        metric.getInterval() == null
                                ? null
                                : metric.getInterval().getUpper());
                fields.add(metric.getUnknownCount());
                row(writer, fields);
            }
        }
    }

    private static void writeComparison(Comparison comparison, Writer writer) throws IOException {
        row(writer, COMPARISON_COLUMNS);
        for (var cohort : comparison.getCohorts()) {
            for (var difference : cohort.getMetricDifferences()) {
                var fields = new ArrayList<Object>(List.of(
                        comparison.getFormatVersion().getValue(),
                        comparison.getComparisonId(),
                        comparison.getBaseline().getRunId(),
                        comparison.getBaseline().getVariantLabel(),
                        comparison.getCandidate().getRunId(),
                        comparison.getCandidate().getVariantLabel(),
                        comparison.getAnalysisVersion(),
                        comparison.getAnalysisSeed(),
                        comparison.getComplete(),
                        cohort.getPolicyId(),
                        cohort.getPolicyVersion(),
                        cohort.getPlayerCount().getValue()));
                fields.add(factionSet(cohort.getFactionSet()));
                fields.add(cohort.getSeatIndex());
                fields.add(cohort.getMatchedBlocks());
                fields.add(cohort.getExcludedBlocks());
                fields.add(difference.getName());
                fields.add(difference.getStatistic());
                fields.addAll(dimensions(difference.getDimensions()));
                fields.add(difference.getUnit());
                fields.add(difference.getBaselineValue());
                fields.add(difference.getCandidateValue());
                fields.add(difference.getDifference());
                fields.add(difference.getStatus());
                fields.add(
                        difference.getInterval() == null
                                ? null
                                : difference.getInterval().getLower());
                fields.add(
                        difference.getInterval() == null
                                ? null
                                : difference.getInterval().getUpper());
                row(writer, fields);
            }
        }
    }

    private static List<Object> counts(ReportCaseCounts counts) {
        return List.of(
                counts.getRequested(),
                counts.getPending(),
                counts.getRunning(),
                counts.getSucceeded(),
                counts.getFailed(),
                counts.getCancelled());
    }

    private static List<Object> dimensions(MetricDimensions dimensions) {
        var fields = new ArrayList<Object>();
        fields.add(dimensions.getFaction());
        fields.add(dimensions.getCardType());
        fields.add(dimensions.getCardGrade());
        fields.add(dimensions.getSpecialAction());
        fields.add(dimensions.getParadoxType());
        fields.add(dimensions.getEndReason());
        fields.add(dimensions.getQuantile());
        return fields;
    }

    private static String factionSet(Collection<?> factions) {
        return factions == null ? null : factions.stream().map(String::valueOf).collect(Collectors.joining("|"));
    }

    private static void row(Writer writer, List<?> fields) throws IOException {
        writer.write(fields.stream().map(AnalysisCsvConverter::field).collect(Collectors.joining(",")));
        writer.write(LINE);
    }

    private static String field(Object value) {
        if (value == null) {
            return "";
        }
        var text = value instanceof BigDecimal decimal ? decimal.toPlainString() : String.valueOf(value);
        if (text.contains(",") || text.contains("\"") || text.contains("\r") || text.contains("\n")) {
            return '"' + text.replace("\"", "\"\"") + '"';
        }
        return text;
    }

    @SafeVarargs
    private static List<String> concat(List<String>... parts) {
        var all = new ArrayList<String>();
        for (var part : parts) {
            all.addAll(part);
        }
        return List.copyOf(all);
    }
}
