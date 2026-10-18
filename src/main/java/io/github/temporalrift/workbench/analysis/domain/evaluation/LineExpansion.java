package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionDimension;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.PolicyReference;
import io.github.temporalrift.workbench.analysis.domain.criterion.Pooling;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.report.CohortComputation;
import io.github.temporalrift.workbench.analysis.domain.report.CohortComputation.Estimate;
import io.github.temporalrift.workbench.analysis.domain.report.CohortKey;
import io.github.temporalrift.workbench.analysis.domain.report.Dimensions;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.analysis.domain.report.ReportCalculator;
import io.github.temporalrift.workbench.analysis.domain.report.Vocabulary;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;

/**
 * Expands each criterion line into one evaluated line per cohort and dimension value it covers. A faction ratio and
 * a failure-code sum are recomputed under every resampling plan of their cohort, so their interval is the interval
 * of the derived value itself.
 */
final class LineExpansion {

    private static final Comparator<List<Faction>> FACTION_SETS = Comparator.comparing((List<Faction> set) ->
            set == null ? "" : set.stream().map(Faction::name).toList().toString());

    private static final Comparator<EvaluatedLine> ORDER = Comparator.comparing(EvaluatedLine::lineId)
            .thenComparing(EvaluatedLine::policyId)
            .thenComparingInt(EvaluatedLine::playerCount)
            .thenComparing(EvaluatedLine::factionSet, Comparator.nullsFirst(FACTION_SETS))
            .thenComparing(
                    line -> line.dimensions().faction(), Comparator.nullsFirst(Comparator.comparing(Faction::name)));

    private LineExpansion() {}

    /**
     * @param cases the cases of the one variant being evaluated
     * @return the evaluated lines ordered by line id, policy, player count, faction set and faction
     */
    static List<EvaluatedLine> expand(
            BalanceCriterion criterion,
            List<AnalyzedCase> cases,
            Map<UUID, CaseFacts> facts,
            AnalysisVersion analysis) {
        var vocabulary = Vocabulary.of(cases, facts.values());
        var failureCodes = ReportCalculator.failureCodes(cases);
        var keys = ReportCalculator.cohortKeys(cases).stream()
                .filter(key -> key.seatIndex() == null)
                .toList();
        var computations = new HashMap<CohortKey, CohortComputation>();
        var lines = new ArrayList<EvaluatedLine>();
        for (var line : criterion.lines()) {
            for (var key : keys) {
                if (covers(line, key)) {
                    var cohort = computations.computeIfAbsent(
                            key,
                            ignored -> CohortComputation.of(key, cases, facts, vocabulary, failureCodes, analysis));
                    lines.addAll(evaluate(line, key, cohort, vocabulary));
                }
            }
        }
        lines.sort(ORDER);
        return lines;
    }

    private static boolean covers(CriterionLine line, CohortKey key) {
        return line.policies().contains(PolicyReference.of(key.policyId(), key.policyVersion()))
                && line.playerCounts().contains(key.playerCount())
                && (line.pooling() == Pooling.POOLED) == (key.factionSet() == null);
    }

    private static List<EvaluatedLine> evaluate(
            CriterionLine line, CohortKey key, CohortComputation cohort, Vocabulary vocabulary) {
        var dimension = line.dimension();
        return switch (line.derivation()) {
            case DIRECT -> {
                var series = cohort.series(line.metric(), reportDimensions(dimension));
                yield List.of(evaluated(
                        line,
                        key,
                        new LineDimensions(null, dimension.endReason(), dimension.quantile(), null),
                        cohort.estimate(direct(series, dimension.quantile()))));
            }
            case RATIO_TO_WIN_REFERENCE -> {
                var all = vocabulary.factions(key.playerCount()).stream()
                        .map(faction -> cohort.series(line.metric(), Dimensions.of(faction)))
                        .flatMap(Optional::stream)
                        .toList();
                var lines = new ArrayList<EvaluatedLine>();
                for (var faction : factionsOf(key, vocabulary)) {
                    var own = cohort.series(line.metric(), Dimensions.of(faction));
                    lines.add(evaluated(
                            line,
                            key,
                            new LineDimensions(faction, null, null, null),
                            cohort.estimate(ratio(own, all))));
                }
                yield lines;
            }
            case SUM_OF_FAILURE_CODES -> {
                var codes = dimension.failureCodes();
                var total = cohort.failureShare(null);
                var counted = codes.stream().map(cohort::failureShare).toList();
                yield List.of(evaluated(
                        line,
                        key,
                        new LineDimensions(null, null, null, codes),
                        cohort.failureEstimate(sum(total, counted))));
            }
        };
    }

    private static Dimensions reportDimensions(CriterionDimension dimension) {
        if (dimension.endReason() != null) {
            return Dimensions.of(dimension.endReason());
        }
        return dimension.quantile() != null ? Dimensions.NONE.at(dimension.quantile()) : Dimensions.NONE;
    }

    private static List<Faction> factionsOf(CohortKey key, Vocabulary vocabulary) {
        if (key.factionSet() != null) {
            return key.factionSet();
        }
        return vocabulary.factions(key.playerCount()).stream()
                .sorted(Comparator.comparing(Faction::name))
                .toList();
    }

    private static ToDoubleFunction<int[]> direct(Optional<BlockSeries> series, Double quantile) {
        return plan -> series.map(found -> quantile == null ? found.ratio(plan) : found.quantile(plan, quantile))
                .orElse(Double.NaN);
    }

    /**
     * The faction's win rate over the win reference {@code R}, winner seat-games over seat-games of the cohort.
     * A plan without any winner has no reference and leaves the ratio undefined.
     */
    private static ToDoubleFunction<int[]> ratio(Optional<BlockSeries> own, List<BlockSeries> everyFaction) {
        return plan -> {
            if (own.isEmpty()) {
                return Double.NaN;
            }
            var winners = 0L;
            var seatGames = 0L;
            for (var series : everyFaction) {
                winners += series.numerator(plan);
                seatGames += series.denominator(plan);
            }
            var ownSeatGames = own.get().denominator(plan);
            if (winners == 0 || ownSeatGames == 0) {
                return Double.NaN;
            }
            return ((double) own.get().numerator(plan) / ownSeatGames) / ((double) winners / seatGames);
        };
    }

    private static ToDoubleFunction<int[]> sum(Optional<BlockSeries> total, List<Optional<BlockSeries>> codes) {
        return plan -> {
            if (total.isEmpty()) {
                return Double.NaN;
            }
            var finished = total.get().denominator(plan);
            if (finished == 0) {
                return Double.NaN;
            }
            var failed = 0L;
            for (var code : codes) {
                failed += code.map(series -> series.numerator(plan)).orElse(0L);
            }
            return (double) failed / finished;
        };
    }

    private static EvaluatedLine evaluated(
            CriterionLine line, CohortKey key, LineDimensions dimensions, Estimate estimate) {
        var deviation = estimate.value() == null ? 0 : line.band().deviation(estimate.value());
        return new EvaluatedLine(
                line.id(),
                line.role(),
                line.metric(),
                dimensions,
                PolicyReference.of(key.policyId(), key.policyVersion()),
                key.playerCount(),
                key.factionSet(),
                line.band(),
                estimate.value(),
                estimate.interval(),
                estimate.status(),
                deviation,
                verdict(line, estimate),
                line.weight());
    }

    private static LineVerdict verdict(CriterionLine line, Estimate estimate) {
        if (estimate.status() != MetricStatus.AVAILABLE || estimate.interval() == null) {
            return LineVerdict.INCONCLUSIVE;
        }
        if (line.band().contains(estimate.interval())) {
            return LineVerdict.MEETS;
        }
        return line.band().excludes(estimate.interval()) ? LineVerdict.FAILS : LineVerdict.INCONCLUSIVE;
    }
}
