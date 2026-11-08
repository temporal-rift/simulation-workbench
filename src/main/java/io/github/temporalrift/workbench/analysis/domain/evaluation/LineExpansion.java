package io.github.temporalrift.workbench.analysis.domain.evaluation;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.ToDoubleFunction;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionDimension;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionPopulation;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionTable;
import io.github.temporalrift.workbench.analysis.domain.criterion.Derivation;
import io.github.temporalrift.workbench.analysis.domain.criterion.IntervalRule;
import io.github.temporalrift.workbench.analysis.domain.criterion.PolicyReference;
import io.github.temporalrift.workbench.analysis.domain.criterion.Pooling;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.report.CohortComputation;
import io.github.temporalrift.workbench.analysis.domain.report.CohortComputation.Estimate;
import io.github.temporalrift.workbench.analysis.domain.report.CohortKey;
import io.github.temporalrift.workbench.analysis.domain.report.Dimensions;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatistic;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.analysis.domain.report.Precision;
import io.github.temporalrift.workbench.analysis.domain.report.ReportCalculator;
import io.github.temporalrift.workbench.analysis.domain.report.Vocabulary;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;
import io.github.temporalrift.workbench.analysis.domain.statistics.ClopperPearson;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Expands each criterion line into one evaluated line per population, cohort and dimension value it covers. A cohort
 * belongs to a population when it was played on the population's table with the seats that table names; a seat metric
 * counts only the population's seats of the cohort's games, a game metric every game. A faction ratio and a
 * failure-code sum are recomputed under every resampling plan of their cohort, so their interval is the interval of
 * the derived value itself. A pooled line whose population has no cohort at a player count the variant played still
 * expands, with no data.
 */
final class LineExpansion {

    private static final double CONFIDENCE = 0.95;

    private static final Comparator<List<Faction>> FACTION_SETS = Comparator.comparing((List<Faction> set) ->
            set == null ? "" : set.stream().map(Faction::name).toList().toString());

    private static final Comparator<EvaluatedLine> ORDER = Comparator.comparing(EvaluatedLine::lineId)
            .thenComparing(EvaluatedLine::tableId)
            .thenComparing(EvaluatedLine::policyId)
            .thenComparingInt(EvaluatedLine::playerCount)
            .thenComparing(EvaluatedLine::factionSet, Comparator.nullsFirst(FACTION_SETS))
            .thenComparing(
                    line -> line.dimensions().faction(), Comparator.nullsFirst(Comparator.comparing(Faction::name)));

    private LineExpansion() {}

    /**
     * @param cases the cases of the one variant being evaluated
     * @return the evaluated lines ordered by line id, table, policy, player count, faction set and faction
     */
    static List<EvaluatedLine> expand(
            BalanceCriterion criterion,
            List<AnalyzedCase> cases,
            Map<UUID, CaseFacts> facts,
            AnalysisVersion analysis) {
        var cohorts = new Cohorts(criterion, cases, facts, analysis);
        var lines = new ArrayList<EvaluatedLine>();
        for (var line : criterion.lines()) {
            for (var placement : cohorts.placements(line)) {
                if (placement.key() == null) {
                    if (line.pooling() == Pooling.POOLED
                            && plays(criterion, placement.population(), placement.playerCount())) {
                        lines.addAll(noData(line, placement.population(), placement.playerCount()));
                    }
                    continue;
                }
                var cohort = cohorts.computation(placement.key());
                for (var measure : measures(line, placement.key(), cohort, cohorts.vocabulary(), placement.seats())) {
                    lines.add(evaluated(line, placement.population(), placement.key(), measure, cohort));
                }
            }
        }
        lines.sort(ORDER);
        return lines;
    }

    /**
     * The deviation of every scored line from its band, at the point estimate and under each resampling plan of its
     * cohort, keyed by what the line measures. Two candidates that played the same seeds share their plans, so their
     * replicates can be subtracted plan by plan.
     */
    static Map<LineInstance, DeviationReplicates> scoredDeviations(
            BalanceCriterion criterion,
            List<AnalyzedCase> cases,
            Map<UUID, CaseFacts> facts,
            AnalysisVersion analysis) {
        var cohorts = new Cohorts(criterion, cases, facts, analysis);
        var deviations = new LinkedHashMap<LineInstance, DeviationReplicates>();
        for (var line : criterion.lines()) {
            if (line.role() != CriterionRole.SCORED) {
                continue;
            }
            for (var placement : cohorts.placements(line)) {
                if (placement.key() == null) {
                    continue;
                }
                var key = placement.key();
                var cohort = cohorts.computation(key);
                for (var measure : measures(line, key, cohort, cohorts.vocabulary(), placement.seats())) {
                    var instance = new LineInstance(
                            line.id(),
                            key.tableId(),
                            placement.population().policy(),
                            key.playerCount(),
                            key.factionSet(),
                            measure.dimensions());
                    deviations.put(instance, DeviationReplicates.of(line.band(), measure.replicates(cohort)));
                }
            }
        }
        return deviations;
    }

    /**
     * Where a line applies: a cohort of the population's table at a player count, with the population's seats in it,
     * or no cohort when the variant played that player count but not on the population's table.
     */
    private record Placement(CriterionPopulation population, int playerCount, CohortKey key, Set<Integer> seats) {}

    /** The cohorts of one variant that pool seats, each with the criterion table its seats play, computed once. */
    private static final class Cohorts {

        private final List<AnalyzedCase> cases;
        private final Map<UUID, CaseFacts> facts;
        private final AnalysisVersion analysis;
        private final Vocabulary vocabulary;
        private final Set<FailureCode> failureCodes;
        private final Map<CohortKey, CriterionTable> tables = new LinkedHashMap<>();
        private final Map<CohortKey, CohortComputation> computations = new HashMap<>();
        private final Set<Integer> playerCounts;

        Cohorts(
                BalanceCriterion criterion,
                List<AnalyzedCase> cases,
                Map<UUID, CaseFacts> facts,
                AnalysisVersion analysis) {
            this.cases = cases;
            this.facts = facts;
            this.analysis = analysis;
            this.vocabulary = Vocabulary.of(cases, facts.values());
            this.failureCodes = ReportCalculator.failureCodes(cases);
            this.playerCounts =
                    cases.stream().map(AnalyzedCase::playerCount).collect(java.util.stream.Collectors.toSet());
            for (var key : ReportCalculator.cohortKeys(cases)) {
                if (key.seatIndex() != null) {
                    continue;
                }
                var references = key.seatPolicies().stream()
                        .map(policy -> PolicyReference.of(policy.id(), policy.version()))
                        .toList();
                criterion.tableOfSeats(key.tableId(), references).ifPresent(table -> tables.put(key, table));
            }
        }

        Vocabulary vocabulary() {
            return vocabulary;
        }

        /** Every population and player count of a line with the cohorts it covers, in criterion order. */
        List<Placement> placements(CriterionLine line) {
            var placements = new ArrayList<Placement>();
            for (var population : line.populations()) {
                for (var playerCount : line.playerCounts()) {
                    var keys = keys(line, population, playerCount);
                    if (keys.isEmpty() && playerCounts.contains(playerCount)) {
                        placements.add(new Placement(population, playerCount, null, Set.of()));
                    }
                    keys.stream()
                            .map(key -> new Placement(population, playerCount, key, seats(key, population)))
                            .filter(placement -> !placement.seats().isEmpty())
                            .forEach(placements::add);
                }
            }
            return placements;
        }

        /** The cohorts of a line's pooling at a player count that were played on the population's table. */
        List<CohortKey> keys(CriterionLine line, CriterionPopulation population, int playerCount) {
            return tables.entrySet().stream()
                    .filter(entry -> entry.getValue().id().equals(population.table()))
                    .map(Map.Entry::getKey)
                    .filter(key -> key.playerCount() == playerCount)
                    .filter(key -> (line.pooling() == Pooling.POOLED) == (key.factionSet() == null))
                    .toList();
        }

        /** The seats of a cohort that the population's policy plays. */
        Set<Integer> seats(CohortKey key, CriterionPopulation population) {
            return tables.get(key).seatsOf(population.policy(), key.playerCount());
        }

        CohortComputation computation(CohortKey key) {
            return computations.computeIfAbsent(
                    key, ignored -> CohortComputation.of(key, cases, facts, vocabulary, failureCodes, analysis));
        }
    }

    /**
     * What a line measures in a cohort: its dimensions, the statistic over the cohort's seed blocks, and for a share
     * its pooled count of events and trials.
     */
    private record Measure(
            LineDimensions dimensions,
            ToDoubleFunction<int[]> statistic,
            boolean failureSeries,
            Supplier<Optional<long[]>> counts) {

        Estimate estimate(CohortComputation cohort) {
            return failureSeries ? cohort.failureEstimate(statistic) : cohort.estimate(statistic);
        }

        CohortComputation.Replicates replicates(CohortComputation cohort) {
            return failureSeries ? cohort.failureReplicates(statistic) : cohort.replicates(statistic);
        }
    }

    private static List<Measure> measures(
            CriterionLine line, CohortKey key, CohortComputation cohort, Vocabulary vocabulary, Set<Integer> seats) {
        var dimension = line.dimension();
        var everySeat = seats.size() == key.playerCount();
        return switch (line.derivation()) {
            case DIRECT -> {
                var dimensions = reportDimensions(dimension);
                if (line.interval() == IntervalRule.BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON
                        && cohort.statistic(line.metric(), dimensions)
                                .filter(MetricStatistic.RATE::equals)
                                .isEmpty()) {
                    throw new IllegalStateException(
                            "Line " + line.id() + ": the Clopper-Pearson union needs a RATE metric");
                }
                var series = everySeat
                        ? cohort.series(line.metric(), dimensions)
                        : cohort.seatSeries(line.metric(), dimensions, seats);
                yield List.of(new Measure(
                        new LineDimensions(null, dimension.endReason(), dimension.quantile(), null),
                        direct(series, dimension.quantile()),
                        false,
                        () -> series.map(found -> new long[] {found.totalNumerator(), found.totalDenominator()})));
            }
            case RATIO_TO_WIN_REFERENCE -> {
                var all = vocabulary.factions(key.playerCount()).stream()
                        .map(faction -> cohort.series(line.metric(), Dimensions.of(faction)))
                        .flatMap(Optional::stream)
                        .toList();
                var measures = new ArrayList<Measure>();
                for (var faction : factionsOf(key, vocabulary)) {
                    var own = everySeat
                            ? cohort.series(line.metric(), Dimensions.of(faction))
                            : cohort.seatSeries(line.metric(), Dimensions.of(faction), seats);
                    measures.add(new Measure(
                            new LineDimensions(faction, null, null, null), ratio(own, all), false, Optional::empty));
                }
                yield measures;
            }
            case SUM_OF_FAILURE_CODES -> {
                var codes = dimension.failureCodes();
                var total = cohort.failureShare(null);
                var counted = codes.stream().map(cohort::failureShare).toList();
                yield List.of(new Measure(
                        new LineDimensions(null, null, null, codes),
                        sum(total, counted),
                        true,
                        () -> total.map(found -> new long[] {
                            counted.stream()
                                    .mapToLong(code -> code.map(BlockSeries::totalNumerator)
                                            .orElse(0L))
                                    .sum(),
                            found.totalDenominator()
                        })));
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
     * The faction's win rate on the population's seats over the win reference {@code R}, winner seat-games over
     * seat-games of every seat of the cohort. A plan without any winner has no reference and leaves the ratio
     * undefined.
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
            CriterionLine line,
            CriterionPopulation population,
            CohortKey key,
            Measure measure,
            CohortComputation cohort) {
        var estimate = measure.estimate(cohort);
        if (line.interval() == IntervalRule.BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON) {
            estimate = withExactBound(estimate, measure.counts().get());
        }
        var deviation = estimate.value() == null ? 0 : line.band().deviation(estimate.value());
        return new EvaluatedLine(
                line.id(),
                line.role(),
                line.metric(),
                measure.dimensions(),
                key.tableId(),
                population.policy(),
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

    /**
     * The estimate with its interval widened to the union with the Clopper–Pearson interval of the pooled counts. An
     * estimate without an interval keeps its status: too few blocks stay an insufficient sample.
     */
    private static Estimate withExactBound(Estimate estimate, Optional<long[]> counts) {
        if (estimate.interval() == null || counts.isEmpty() || counts.get()[1] == 0) {
            return estimate;
        }
        var exact = ClopperPearson.interval(counts.get()[0], counts.get()[1], CONFIDENCE);
        var interval = new Interval(
                Math.min(estimate.interval().lower(), Precision.round(exact[0])),
                Math.max(estimate.interval().upper(), Precision.round(exact[1])));
        return new Estimate(estimate.value(), estimate.status(), interval);
    }

    /** Whether the population's policy plays a seat of its table at the player count. */
    private static boolean plays(BalanceCriterion criterion, CriterionPopulation population, int playerCount) {
        return criterion
                .table(population.table())
                .filter(table ->
                        !table.seatsOf(population.policy(), playerCount).isEmpty())
                .isPresent();
    }

    /** One line per dimension value of a pooled line whose population has no cohort in the variant. */
    private static List<EvaluatedLine> noData(CriterionLine line, CriterionPopulation population, int playerCount) {
        var dimension = line.dimension();
        var values = new ArrayList<LineDimensions>();
        if (line.derivation() == Derivation.RATIO_TO_WIN_REFERENCE) {
            EnumSet.allOf(Faction.class).stream()
                    .sorted(Comparator.comparing(Faction::name))
                    .forEach(faction -> values.add(new LineDimensions(faction, null, null, null)));
        } else if (line.derivation() == Derivation.SUM_OF_FAILURE_CODES) {
            values.add(new LineDimensions(null, null, null, dimension.failureCodes()));
        } else {
            values.add(new LineDimensions(null, dimension.endReason(), dimension.quantile(), null));
        }
        return values.stream()
                .map(dimensions -> new EvaluatedLine(
                        line.id(),
                        line.role(),
                        line.metric(),
                        dimensions,
                        population.table(),
                        population.policy(),
                        playerCount,
                        null,
                        line.band(),
                        null,
                        null,
                        MetricStatus.NO_DATA,
                        0,
                        LineVerdict.INCONCLUSIVE,
                        line.weight()))
                .toList();
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
