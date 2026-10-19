package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;

/** Builds a run's stratified report from its cases and the facts of its succeeded cases. */
public final class ReportCalculator {

    private ReportCalculator() {}

    public static RunReport report(
            UUID runId,
            String manifestDigest,
            AnalysisVersion analysis,
            List<AnalyzedCase> cases,
            Map<UUID, CaseFacts> facts) {
        var vocabulary = Vocabulary.of(cases, facts.values());
        var failureCodes = failureCodes(cases);
        var cohorts = cohortKeys(cases).stream()
                .map(key -> CohortComputation.of(key, cases, facts, vocabulary, failureCodes, analysis)
                        .cohort())
                .toList();
        return new RunReport(runId, manifestDigest, analysis, CaseCounts.of(cases), cohorts);
    }

    /** The failure codes that occur anywhere in the run. */
    public static Set<FailureCode> failureCodes(List<AnalyzedCase> cases) {
        var codes = EnumSet.noneOf(FailureCode.class);
        cases.stream().map(AnalyzedCase::failureCode).filter(Objects::nonNull).forEach(codes::add);
        return codes;
    }

    /**
     * For every variant and policy in matrix order and every player count ascending: the pooled cohort, one per
     * seat, one per faction set in matrix order, and one per faction set and seat.
     */
    public static List<CohortKey> cohortKeys(List<AnalyzedCase> cases) {
        var groups = new LinkedHashMap<List<String>, Map<Integer, LinkedHashSet<List<Faction>>>>();
        for (var analyzedCase : cases) {
            groups.computeIfAbsent(
                            List.of(analyzedCase.variantLabel(), analyzedCase.policyId(), analyzedCase.policyVersion()),
                            ignored -> new TreeMap<>())
                    .computeIfAbsent(analyzedCase.playerCount(), ignored -> new LinkedHashSet<>())
                    .add(CohortKey.factionSetOf(analyzedCase));
        }
        var keys = new ArrayList<CohortKey>();
        groups.forEach((group, setsByCount) -> setsByCount.forEach((playerCount, sets) -> {
            var variant = group.get(0);
            var policyId = group.get(1);
            var policyVersion = group.get(2);
            keys.add(new CohortKey(variant, policyId, policyVersion, playerCount, null, null));
            for (var seat = 0; seat < playerCount; seat++) {
                keys.add(new CohortKey(variant, policyId, policyVersion, playerCount, null, seat));
            }
            sets.forEach(set -> keys.add(new CohortKey(variant, policyId, policyVersion, playerCount, set, null)));
            sets.forEach(set -> {
                for (var seat = 0; seat < playerCount; seat++) {
                    keys.add(new CohortKey(variant, policyId, policyVersion, playerCount, set, seat));
                }
            });
        }));
        return keys;
    }

    static Metric metric(MetricHeader definition, BlockSeries series, int blocks, BlockBootstrap bootstrap) {
        var all = BlockBootstrap.identity(blocks);
        var quantile = definition.dimensions().quantile();
        var denominator = series.denominator(all);
        var numerator = quantile == null ? (double) series.numerator(all) : null;
        if (denominator == 0) {
            return new Metric(
                    definition.name(),
                    definition.statistic(),
                    definition.dimensions(),
                    definition.unit(),
                    null,
                    numerator,
                    0,
                    MetricStatus.NO_DATA,
                    null,
                    series.unknown());
        }
        var value = quantile == null ? series.ratio(all) : series.quantile(all, quantile);
        Interval interval = null;
        var status = MetricStatus.INSUFFICIENT_SAMPLE;
        if (bootstrap != null) {
            status = MetricStatus.AVAILABLE;
            interval = bootstrap
                    .interval(plan -> quantile == null ? series.ratio(plan) : series.quantile(plan, quantile))
                    .map(bounds -> new Interval(Precision.round(bounds[0]), Precision.round(bounds[1])))
                    .orElse(null);
        }
        return new Metric(
                definition.name(),
                definition.statistic(),
                definition.dimensions(),
                definition.unit(),
                Precision.round(value),
                numerator,
                denominator,
                status,
                interval,
                series.unknown());
    }
}
