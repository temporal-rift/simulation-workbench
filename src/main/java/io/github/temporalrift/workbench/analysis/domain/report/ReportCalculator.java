package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
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
import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.Faction;

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
        var members = membersByCohort(cases);
        var cohorts = cohortKeys(cases).parallelStream()
                .map(key -> CohortComputation.ofMembers(
                                key, members.get(key.withoutSeat()), facts, vocabulary, failureCodes, analysis)
                        .cohort())
                .toList();
        return new RunReport(runId, manifestDigest, analysis, CaseCounts.of(cases), cohorts);
    }

    /** Each cohort's member cases in case order, grouped in one pass; a seat cohort has its seat-pooling cohort's. */
    private static Map<CohortKey, List<AnalyzedCase>> membersByCohort(List<AnalyzedCase> cases) {
        var members = new HashMap<CohortKey, List<AnalyzedCase>>();
        for (var analyzedCase : cases) {
            var pooled = CohortKey.pooledOf(analyzedCase);
            members.computeIfAbsent(pooled, ignored -> new ArrayList<>()).add(analyzedCase);
            members.computeIfAbsent(
                            pooled.withFactionSet(CohortKey.factionSetOf(analyzedCase)), ignored -> new ArrayList<>())
                    .add(analyzedCase);
        }
        return members;
    }

    /** The failure codes that occur anywhere in the run. */
    public static Set<FailureCode> failureCodes(List<AnalyzedCase> cases) {
        var codes = EnumSet.noneOf(FailureCode.class);
        cases.stream().map(AnalyzedCase::failureCode).filter(Objects::nonNull).forEach(codes::add);
        return codes;
    }

    /**
     * For every variant and table in matrix order and every player count ascending: the pooled cohort, one per
     * seat, one per faction set in matrix order, and one per faction set and seat.
     */
    public static List<CohortKey> cohortKeys(List<AnalyzedCase> cases) {
        var groups = new LinkedHashMap<List<String>, Map<Integer, Cell>>();
        for (var analyzedCase : cases) {
            groups.computeIfAbsent(
                            List.of(analyzedCase.variantLabel(), analyzedCase.tableId()), ignored -> new TreeMap<>())
                    .computeIfAbsent(analyzedCase.playerCount(), ignored -> new Cell(analyzedCase.seatPolicies()))
                    .sets()
                    .add(CohortKey.factionSetOf(analyzedCase));
        }
        var keys = new ArrayList<CohortKey>();
        groups.forEach((group, cellsByCount) -> cellsByCount.forEach((playerCount, cell) -> {
            var variant = group.get(0);
            var table = group.get(1);
            var policies = cell.seatPolicies();
            keys.add(new CohortKey(variant, table, policies, playerCount, null, null));
            for (var seat = 0; seat < playerCount; seat++) {
                keys.add(new CohortKey(variant, table, policies, playerCount, null, seat));
            }
            cell.sets().forEach(set -> keys.add(new CohortKey(variant, table, policies, playerCount, set, null)));
            cell.sets().forEach(set -> {
                for (var seat = 0; seat < playerCount; seat++) {
                    keys.add(new CohortKey(variant, table, policies, playerCount, set, seat));
                }
            });
        }));
        return keys;
    }

    /** The faction sets a table played at one player count, with the policy of each seat. */
    private record Cell(List<SeatPolicy> seatPolicies, LinkedHashSet<List<Faction>> sets) {

        Cell(List<SeatPolicy> seatPolicies) {
            this(seatPolicies, new LinkedHashSet<>());
        }
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
