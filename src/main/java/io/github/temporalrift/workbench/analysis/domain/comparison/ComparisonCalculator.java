package io.github.temporalrift.workbench.analysis.domain.comparison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.report.BlockTallies;
import io.github.temporalrift.workbench.analysis.domain.report.CohortKey;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricCatalog;
import io.github.temporalrift.workbench.analysis.domain.report.MetricDefinition;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.analysis.domain.report.Precision;
import io.github.temporalrift.workbench.analysis.domain.report.ReportCalculator;
import io.github.temporalrift.workbench.analysis.domain.report.Vocabulary;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockSeries;
import io.github.temporalrift.workbench.analysis.domain.statistics.SplitMix64;

/**
 * Pairs the two sides' cases and estimates differences from complete matched blocks only. A pair shares seed,
 * player count, policy and seat-to-faction assignment; in a cohort a seed is matched only when every pair of the
 * cohort in that seed succeeded on both sides, so dropping a failed pair never unbalances seats or factions.
 */
public final class ComparisonCalculator {

    private ComparisonCalculator() {}

    /**
     * @param baselineCases the baseline run's cases of the baseline variant
     * @param candidateCases the candidate run's cases of the candidate variant
     */
    public static ComparisonResult compare(
            ComparisonDefinition definition,
            List<AnalyzedCase> baselineCases,
            List<AnalyzedCase> candidateCases,
            Map<UUID, CaseFacts> facts) {
        var candidatesByCoordinate = new HashMap<Coordinate, AnalyzedCase>();
        candidateCases.forEach(candidate -> candidatesByCoordinate.put(Coordinate.of(candidate), candidate));
        var pairs = new ArrayList<Pair>();
        baselineCases.forEach(baseline -> {
            var candidate = candidatesByCoordinate.get(Coordinate.of(baseline));
            if (candidate != null) {
                pairs.add(new Pair(baseline, candidate));
            }
        });
        var excluded = pairs.stream()
                .filter(pair -> !pair.succeeded())
                .map(ComparisonCalculator::excluded)
                .toList();
        var vocabulary = Vocabulary.of(baselineCases, sideFacts(baselineCases, facts))
                .and(Vocabulary.of(candidateCases, sideFacts(candidateCases, facts)));
        var cohorts = ReportCalculator.cohortKeys(baselineCases).stream()
                .map(key -> cohort(definition, key, pairs, facts, vocabulary))
                .toList();
        return new ComparisonResult(
                definition,
                cohorts.stream()
                        .filter(cohort -> cohort.key().factionSet() == null
                                && cohort.key().seatIndex() == null)
                        .mapToInt(ComparisonCohort::matchedBlocks)
                        .sum(),
                (int) excluded.stream()
                        .filter(pair -> pair.reason() == ExclusionReason.UNFINISHED)
                        .count(),
                (int) excluded.stream()
                        .filter(pair -> pair.reason() == ExclusionReason.FAILED_COUNTERPART)
                        .count(),
                excluded,
                cohorts);
    }

    private static ComparisonCohort cohort(
            ComparisonDefinition definition,
            CohortKey key,
            List<Pair> pairs,
            Map<UUID, CaseFacts> facts,
            Vocabulary vocabulary) {
        var members =
                pairs.stream().filter(pair -> key.contains(pair.baseline())).toList();
        var seeds = new HashSet<String>();
        var incomplete = new HashSet<String>();
        members.forEach(pair -> {
            seeds.add(pair.seed());
            if (!pair.succeeded()) {
                incomplete.add(pair.seed());
            }
        });
        var baselineGames = new LinkedHashMap<String, List<CaseFacts>>();
        var candidateGames = new LinkedHashMap<String, List<CaseFacts>>();
        members.stream().filter(pair -> !incomplete.contains(pair.seed())).forEach(pair -> {
            var baselineFacts = facts.get(pair.baseline().caseId());
            if (baselineFacts != null) {
                baselineGames
                        .computeIfAbsent(pair.seed(), seed -> new ArrayList<>())
                        .add(baselineFacts);
            }
            var candidateFacts = facts.get(pair.candidate().caseId());
            if (candidateFacts != null) {
                candidateGames
                        .computeIfAbsent(pair.seed(), seed -> new ArrayList<>())
                        .add(candidateFacts);
            }
        });
        // A paired block needs evidence on both sides to stay aligned by seed.
        var pairedSeeds = new HashSet<>(baselineGames.keySet());
        pairedSeeds.retainAll(candidateGames.keySet());
        baselineGames.keySet().retainAll(pairedSeeds);
        candidateGames.keySet().retainAll(pairedSeeds);
        var definitions = MetricCatalog.definitions(key, vocabulary);
        var baseline = BlockTallies.collect(definitions, baselineGames, key.seatIndex());
        var candidate = BlockTallies.collect(definitions, candidateGames, key.seatIndex());
        var matched = baseline.seeds().size();
        var bootstrap = matched < 2
                ? null
                : BlockBootstrap.of(
                        matched,
                        SplitMix64.seededBy(
                                "comparison",
                                definition.analysis().version(),
                                definition.analysis().seedText(),
                                key.text(),
                                definition.candidate().variantLabel()));
        var differences = new ArrayList<MetricDifference>();
        for (var index = 0; index < definitions.size(); index++) {
            differences.add(difference(
                    definitions.get(index), baseline.series(index), candidate.series(index), matched, bootstrap));
        }
        return new ComparisonCohort(key, matched, seeds.size() - matched, differences);
    }

    private static MetricDifference difference(
            MetricDefinition definition,
            BlockSeries baseline,
            BlockSeries candidate,
            int blocks,
            BlockBootstrap bootstrap) {
        var all = BlockBootstrap.identity(blocks);
        var quantile = definition.dimensions().quantile();
        var baselineValue = statistic(baseline, all, quantile);
        var candidateValue = statistic(candidate, all, quantile);
        if (Double.isNaN(baselineValue) || Double.isNaN(candidateValue)) {
            return new MetricDifference(
                    definition.name(),
                    definition.statistic(),
                    definition.dimensions(),
                    definition.unit(),
                    Precision.round(baselineValue),
                    Precision.round(candidateValue),
                    null,
                    MetricStatus.NO_DATA,
                    null);
        }
        Interval interval = null;
        var status = MetricStatus.INSUFFICIENT_SAMPLE;
        if (bootstrap != null) {
            status = MetricStatus.AVAILABLE;
            interval = bootstrap
                    .interval(plan -> statistic(candidate, plan, quantile) - statistic(baseline, plan, quantile))
                    .map(bounds -> new Interval(Precision.round(bounds[0]), Precision.round(bounds[1])))
                    .orElse(null);
        }
        return new MetricDifference(
                definition.name(),
                definition.statistic(),
                definition.dimensions(),
                definition.unit(),
                Precision.round(baselineValue),
                Precision.round(candidateValue),
                Precision.round(candidateValue - baselineValue),
                status,
                interval);
    }

    private static double statistic(BlockSeries series, int[] plan, Double quantile) {
        return quantile == null ? series.ratio(plan) : series.quantile(plan, quantile);
    }

    private static List<CaseFacts> sideFacts(List<AnalyzedCase> cases, Map<UUID, CaseFacts> facts) {
        return cases.stream()
                .map(analyzedCase -> facts.get(analyzedCase.caseId()))
                .filter(Objects::nonNull)
                .toList();
    }

    private static ExcludedPair excluded(Pair pair) {
        var baselineState = pair.baseline().status();
        var candidateState = pair.candidate().status();
        var reason = isFailure(baselineState) || isFailure(candidateState)
                ? ExclusionReason.FAILED_COUNTERPART
                : ExclusionReason.UNFINISHED;
        return new ExcludedPair(
                pair.seed(),
                pair.baseline().playerCount(),
                pair.baseline().policyId(),
                pair.baseline().policyVersion(),
                pair.baseline().seatFactions(),
                pair.baseline().caseId(),
                pair.candidate().caseId(),
                baselineState,
                candidateState,
                reason);
    }

    private static boolean isFailure(CaseStatus status) {
        return Set.of(CaseStatus.FAILED, CaseStatus.CANCELLED).contains(status);
    }

    private record Pair(AnalyzedCase baseline, AnalyzedCase candidate) {

        String seed() {
            return baseline.seed();
        }

        boolean succeeded() {
            return baseline.succeeded() && candidate.succeeded();
        }
    }

    /** What makes two cases of compatible runs the same game but for the variant. */
    private record Coordinate(
            String seed, int playerCount, String policyId, String policyVersion, List<Faction> seats) {

        static Coordinate of(AnalyzedCase analyzedCase) {
            return new Coordinate(
                    analyzedCase.seed(),
                    analyzedCase.playerCount(),
                    analyzedCase.policyId(),
                    analyzedCase.policyVersion(),
                    analyzedCase.seatFactions());
        }
    }
}
