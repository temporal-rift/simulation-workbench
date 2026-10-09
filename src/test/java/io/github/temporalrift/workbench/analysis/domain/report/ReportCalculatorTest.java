package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

class ReportCalculatorTest {

    private static final UUID RUN = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final String DIGEST = "a".repeat(64);
    private static final List<Faction> EPW = List.of(Faction.ERASERS, Faction.PROPHETS, Faction.WEAVERS);

    private final List<AnalyzedCase> cases = new ArrayList<>();
    private final Map<UUID, CaseFacts> facts = new HashMap<>();

    @Test
    void sharedWinsCountForEveryWinnerAndFailedCasesStayOutsideTheDenominator() {
        // Erasers sit in seat 0 and win 4 of 10 games, one of them shared with Weavers.
        for (var game = 0; game < 10; game++) {
            var winners = game < 3 ? Set.of(0) : game == 3 ? Set.of(0, 2) : Set.of(1);
            succeeded(String.valueOf(game + 1), EPW, winners);
        }
        failed("11", EPW);
        failed("12", EPW);

        var report = report();
        var pooled = report.cohorts().getFirst();

        assertThat(report.complete()).isFalse();
        assertThat(pooled.caseCounts().failed()).isEqualTo(2);
        assertThat(pooled.eligibleGames()).isEqualTo(10);
        var winRate = metric(pooled, "faction_win_rate", Faction.ERASERS);
        assertThat(winRate.numerator()).isEqualTo(4.0);
        assertThat(winRate.denominator()).isEqualTo(10);
        assertThat(winRate.value()).isEqualTo(0.4);
        assertThat(metric(pooled, "faction_shared_win_rate", Faction.ERASERS).value())
                .isEqualTo(0.1);
        assertThat(metric(pooled, "faction_win_rate", Faction.WEAVERS).numerator())
                .isEqualTo(1.0);
        assertThat(metric(pooled, "faction_win_rate", Faction.PROPHETS).numerator())
                .isEqualTo(6.0);
        assertThat(metric(pooled, "shared_win_rate", null).value()).isEqualTo(0.1);
    }

    @Test
    void oneSeedIsOneIndependentBlockWithoutAnInterval() {
        succeeded("42", EPW, Set.of(0));
        succeeded("42", List.of(Faction.PROPHETS, Faction.WEAVERS, Faction.ERASERS), Set.of(2));
        succeeded("42", List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS), Set.of(1));

        var pooled = report().cohorts().getFirst();

        assertThat(pooled.independentBlocks()).isEqualTo(1);
        assertThat(pooled.metrics())
                .filteredOn(metric -> metric.status() != MetricStatus.NO_DATA)
                .isNotEmpty()
                .allSatisfy(metric -> {
                    assertThat(metric.status()).isEqualTo(MetricStatus.INSUFFICIENT_SAMPLE);
                    assertThat(metric.interval()).isNull();
                    assertThat(metric.value()).isNotNull();
                });
        assertThat(metric(pooled, "faction_win_rate", Faction.ERASERS).value()).isEqualTo(1.0);
    }

    @Test
    void identicalBlocksGiveADegenerateIntervalAtTheirValue() {
        for (var seed = 1; seed <= 6; seed++) {
            succeeded(String.valueOf(seed), EPW, Set.of(0));
            succeeded(String.valueOf(seed), EPW, Set.of(1));
        }

        var winRate = metric(report().cohorts().getFirst(), "faction_win_rate", Faction.ERASERS);

        assertThat(winRate.status()).isEqualTo(MetricStatus.AVAILABLE);
        assertThat(winRate.value()).isEqualTo(0.5);
        assertThat(winRate.interval()).isEqualTo(new Interval(0.5, 0.5));
    }

    @Test
    void varyingBlocksGiveAnIntervalAroundTheValueThatRegeneratesIdentically() {
        for (var seed = 1; seed <= 8; seed++) {
            succeeded(String.valueOf(seed), EPW, Set.of(seed % 3 == 0 ? 1 : 0));
        }

        var first = report();
        var winRate = metric(first.cohorts().getFirst(), "faction_win_rate", Faction.ERASERS);

        assertThat(winRate.value()).isEqualTo(0.75);
        assertThat(winRate.interval().lower()).isLessThan(0.75);
        assertThat(winRate.interval().upper()).isEqualTo(1.0);
        assertThat(report()).isEqualTo(first);
    }

    @Test
    void aFactionAbsentFromAFactionSetHasNoData() {
        succeeded("1", EPW, Set.of(0));
        succeeded("1", List.of(Faction.ACTIVISTS, Faction.PROPHETS, Faction.WEAVERS), Set.of(0));

        var cohorts = report().cohorts();
        var erasersSet = cohorts.stream()
                .filter(cohort ->
                        EPW.equals(cohort.key().factionSet()) && cohort.key().seatIndex() == null)
                .findFirst()
                .orElseThrow();

        var activists = metric(erasersSet, "faction_win_rate", Faction.ACTIVISTS);
        assertThat(activists.status()).isEqualTo(MetricStatus.NO_DATA);
        assertThat(activists.denominator()).isZero();
        assertThat(activists.value()).isNull();
    }

    @Test
    void cohortsArePooledThenSeatsThenFactionSetsThenFactionSetsBySeat() {
        var sets = List.of(
                EPW,
                List.of(Faction.ACTIVISTS, Faction.PROPHETS, Faction.WEAVERS),
                List.of(Faction.ACTIVISTS, Faction.ERASERS, Faction.WEAVERS));
        sets.forEach(set -> succeeded("1", set, Set.of(0)));

        var keys = report().cohorts().stream().map(Cohort::key).toList();

        assertThat(keys).hasSize(1 + 3 + 3 + 9);
        assertThat(keys.getFirst().factionSet()).isNull();
        assertThat(keys.subList(1, 4)).extracting(CohortKey::seatIndex).containsExactly(0, 1, 2);
        assertThat(keys.get(4).factionSet()).isEqualTo(EPW);
        assertThat(keys.get(7)).isEqualTo(new CohortKey("base", "random", "1.0.0", 3, EPW, 0));
        assertThat(keys.get(8).seatIndex()).isEqualTo(1);
    }

    @Test
    void seatCohortsCarryNoGameMetrics() {
        succeeded("1", EPW, Set.of(0));

        var seat = report().cohorts().get(1);

        assertThat(seat.key().seatIndex()).isZero();
        assertThat(seat.metrics()).extracting(Metric::name).doesNotContain("ending_cause_rate", "eras");
        assertThat(metric(seat, "faction_win_rate", Faction.ERASERS).denominator())
                .isEqualTo(1);
        assertThat(metric(seat, "faction_win_rate", Faction.WEAVERS).status()).isEqualTo(MetricStatus.NO_DATA);
    }

    private RunReport report() {
        return ReportCalculator.report(RUN, DIGEST, AnalysisVersion.CURRENT, cases, facts);
    }

    private void succeeded(String seed, List<Faction> seats, Set<Integer> winners) {
        var outcome = new CaseOutcome(EndReason.WIN_CONDITION_MET, winners, List.of(20, 15, 10), 3, 9, 30);
        var analyzed = analyzed(seed, seats, CaseStatus.SUCCEEDED, outcome);
        cases.add(analyzed);
        facts.put(
                analyzed.caseId(),
                FactExtractor.extract(
                        analyzed.caseId(), seats, outcome, new GameRecord(List.of(), List.of(), List.of())));
    }

    private void failed(String seed, List<Faction> seats) {
        cases.add(analyzed(seed, seats, CaseStatus.FAILED, null));
    }

    private AnalyzedCase analyzed(String seed, List<Faction> seats, CaseStatus status, CaseOutcome outcome) {
        return new AnalyzedCase(
                new UUID(1, cases.size()), "base", seed, seats.size(), "random", "1.0.0", seats, status, outcome);
    }

    private static Metric metric(Cohort cohort, String name, Faction faction) {
        return cohort.metrics().stream()
                .filter(metric -> metric.name().equals(name)
                        && metric.dimensions().faction() == faction
                        && metric.dimensions().quantile() == null)
                .findFirst()
                .orElseThrow();
    }
}
