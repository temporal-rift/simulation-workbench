package io.github.temporalrift.workbench.analysis.domain.comparison;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.fact.CaseFacts;
import io.github.temporalrift.workbench.analysis.domain.fact.FactExtractor;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.CaseStatus;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;

class ComparisonCalculatorTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final ComparisonSide BASELINE = new ComparisonSide(RUN, "threshold-20");
    private static final ComparisonSide CANDIDATE = new ComparisonSide(RUN, "threshold-22");
    private static final List<Faction> EPW = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);
    private static final List<Faction> PWE = List.of(Faction.PROPHETS, Faction.WEAVERS, Faction.ERASERS);

    private final List<AnalyzedCase> baselineCases = new ArrayList<>();
    private final List<AnalyzedCase> candidateCases = new ArrayList<>();
    private final Map<UUID, CaseFacts> facts = new HashMap<>();

    @Test
    void theSameSideTwiceIsInvalid() {
        var experiment = experiment(Map.of("seeds", "[1,2]"));

        assertThatThrownBy(() -> ComparisonDefinition.define(
                        new UUID(0, 9), BASELINE, experiment, BASELINE, experiment, AnalysisVersion.CURRENT))
                .isInstanceOf(InvalidComparisonException.class);
    }

    @Test
    void aVariantMissingFromItsExperimentIsInvalid() {
        var experiment = experiment(Map.of("seeds", "[1,2]"));

        assertThatThrownBy(() -> ComparisonDefinition.define(
                        new UUID(0, 9),
                        BASELINE,
                        experiment,
                        new ComparisonSide(RUN, "threshold-99"),
                        experiment,
                        AnalysisVersion.CURRENT))
                .isInstanceOf(InvalidComparisonException.class);
    }

    @Test
    void differentGameplayDefinitionsAreIncomparableAndNamed() {
        var baseline = experiment(Map.of("seeds", "[1,2]", "policies", "[random]"));
        var candidate = experiment(Map.of("seeds", "[1,2,3]", "policies", "[random]"));

        assertThatThrownBy(() -> ComparisonDefinition.define(
                        new UUID(0, 9), BASELINE, baseline, CANDIDATE, candidate, AnalysisVersion.CURRENT))
                .isInstanceOfSatisfying(
                        IncomparableRunsException.class,
                        error -> assertThat(error.differences()).containsExactly("seeds"));
    }

    @Test
    void aChangedRulesVariantIsADeclaredDifference() {
        var experiment = experiment(Map.of("seeds", "[1,2]"));

        var definition = definition(experiment);

        assertThat(definition.declaredDifferences())
                .containsExactly(new DeclaredDifference(DifferenceField.RULES, "1".repeat(64), "2".repeat(64)));
    }

    @Test
    void aFailedCounterpartExcludesItsSeedVisiblyFromEveryCohortHoldingIt() {
        for (var seed = 1; seed <= 3; seed++) {
            pair(String.valueOf(seed), EPW, Set.of(0), Set.of(0));
            pair(String.valueOf(seed), PWE, Set.of(0), Set.of(2));
        }
        var failed = new UUID(7, 7);
        candidateCases.set(
                1, new AnalyzedCase(failed, "threshold-22", "1", 3, "random", "1.0.0", PWE, CaseStatus.FAILED, null));

        var result = compare();
        var pooled = result.cohorts().getFirst();

        assertThat(result.complete()).isFalse();
        assertThat(result.failedCounterparts()).isEqualTo(1);
        assertThat(result.excludedPairs()).singleElement().satisfies(pair -> {
            assertThat(pair.reason()).isEqualTo(ExclusionReason.FAILED_COUNTERPART);
            assertThat(pair.candidateCaseId()).isEqualTo(failed);
            assertThat(pair.baselineState()).isEqualTo(CaseStatus.SUCCEEDED);
        });
        assertThat(pooled.matchedBlocks()).isEqualTo(2);
        assertThat(pooled.excludedBlocks()).isEqualTo(1);
        assertThat(result.matchedBlocks()).isEqualTo(2);
    }

    @Test
    void anUnfinishedPairKeepsTheComparisonPartial() {
        pair("1", EPW, Set.of(0), Set.of(0));
        candidateCases.set(
                0,
                new AnalyzedCase(
                        new UUID(8, 8), "threshold-22", "1", 3, "random", "1.0.0", EPW, CaseStatus.RUNNING, null));

        var result = compare();

        assertThat(result.complete()).isFalse();
        assertThat(result.unmatchedCases()).isEqualTo(1);
        assertThat(result.excludedPairs()).extracting(ExcludedPair::reason).containsExactly(ExclusionReason.UNFINISHED);
    }

    @Test
    void oneMatchedSeedGivesValuesWithoutAnInterval() {
        pair("1", EPW, Set.of(0), Set.of(1));

        var winRate = erasersWinRate(compare());

        assertThat(winRate.status()).isEqualTo(MetricStatus.INSUFFICIENT_SAMPLE);
        assertThat(winRate.baselineValue()).isEqualTo(1.0);
        assertThat(winRate.candidateValue()).isEqualTo(0.0);
        assertThat(winRate.difference()).isEqualTo(-1.0);
        assertThat(winRate.interval()).isNull();
    }

    @Test
    void aConsistentDifferenceAcrossSeedsHasADegenerateInterval() {
        for (var seed = 1; seed <= 5; seed++) {
            pair(String.valueOf(seed), EPW, Set.of(0), Set.of(0));
            pair(String.valueOf(seed), PWE, Set.of(0), Set.of(2));
        }

        var result = compare();
        var winRate = erasersWinRate(result);

        assertThat(result.complete()).isTrue();
        assertThat(winRate.baselineValue()).isEqualTo(0.5);
        assertThat(winRate.candidateValue()).isEqualTo(1.0);
        assertThat(winRate.difference()).isEqualTo(0.5);
        assertThat(winRate.status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(winRate.interval()).isEqualTo(new Interval(0.5, 0.5));
        assertThat(compare()).isEqualTo(result);
    }

    private MetricDifference erasersWinRate(ComparisonResult result) {
        return result.cohorts().getFirst().differences().stream()
                .filter(difference -> difference.name().equals("faction_win_rate")
                        && difference.dimensions().faction() == Faction.ERASERS)
                .findFirst()
                .orElseThrow();
    }

    private ComparisonResult compare() {
        return ComparisonCalculator.compare(
                definition(experiment(Map.of("seeds", "[1]"))), baselineCases, candidateCases, facts);
    }

    private static ComparisonDefinition definition(ExperimentDefinition experiment) {
        return ComparisonDefinition.define(
                new UUID(0, 9), BASELINE, experiment, CANDIDATE, experiment, AnalysisVersion.CURRENT);
    }

    private static ExperimentDefinition experiment(Map<String, String> gameplay) {
        return new ExperimentDefinition(
                new TreeMap<>(gameplay),
                Map.of(
                        "threshold-20", new ExperimentDefinition.VariantDigests("1".repeat(64), "c".repeat(64)),
                        "threshold-22", new ExperimentDefinition.VariantDigests("2".repeat(64), "c".repeat(64))));
    }

    private void pair(String seed, List<Faction> seats, Set<Integer> baselineWinners, Set<Integer> candidateWinners) {
        baselineCases.add(succeeded("threshold-20", seed, seats, baselineWinners));
        candidateCases.add(succeeded("threshold-22", seed, seats, candidateWinners));
    }

    private AnalyzedCase succeeded(String variant, String seed, List<Faction> seats, Set<Integer> winners) {
        var outcome = new CaseOutcome(EndReason.WIN_CONDITION_MET, winners, List.of(20, 15, 10), 3, 9, 30);
        var analyzed = new AnalyzedCase(
                new UUID(2, facts.size()), variant, seed, 3, "random", "1.0.0", seats, CaseStatus.SUCCEEDED, outcome);
        facts.put(
                analyzed.caseId(),
                FactExtractor.extract(
                        analyzed.caseId(), seats, outcome, new GameRecord(List.of(), List.of(), List.of())));
        return analyzed;
    }
}
