package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class ReportReasonOccurrencesTest {

    private static final UUID RUN = new UUID(0, 8);
    private static final String DIGEST = "d".repeat(64);
    private static final List<Faction> SEATS = List.of(Faction.REVISIONISTS, Faction.WEAVERS, Faction.ERASERS);
    private static final AnalysisVersion V5 = new AnalysisVersion("5", 1L);
    private static final String REASON_METRIC = "reason_occurrences_per_era";
    private static final String VOIDED_METRIC = "bet_voided_rate_per_era";

    private final SyntheticRun run = new SyntheticRun("base", "random");

    /**
     * Five eras. The Revisionist rewrites in eras 1 to 3 and records nothing; the Weaver threads in eras 1 and 2 and
     * keeps the first link; the Eraser annihilates in eras 1 and 2, wasting both, and in era 2 also records a cascade
     * penalty twice.
     */
    private static List<GameEvent> events() {
        return List.of(
                new GameEvent.SpecialPlayed(0, 1, SpecialAction.REWRITE),
                new GameEvent.SpecialPlayed(0, 2, SpecialAction.REWRITE),
                new GameEvent.SpecialPlayed(0, 3, SpecialAction.REWRITE),
                new GameEvent.SpecialPlayed(1, 1, SpecialAction.THREAD),
                new GameEvent.SpecialPlayed(1, 2, SpecialAction.THREAD),
                new GameEvent.ScoreRecorded(1, 1, "CHAIN_LINK_ADDED", 3),
                new GameEvent.SpecialPlayed(2, 1, SpecialAction.ANNIHILATE),
                new GameEvent.SpecialPlayed(2, 2, SpecialAction.ANNIHILATE),
                new GameEvent.ScoreRecorded(2, 1, "ANNIHILATION_WASTED", 0),
                new GameEvent.ScoreRecorded(
                        2, 2, "ANNIHILATION_WASTED,PARADOX_CASCADE_PENALTY,PARADOX_CASCADE_PENALTY", -4));
    }

    @BeforeEach
    void play() {
        run.played("1", SEATS, Set.of(0), Map.of(0, WinPath.FACTION_OBJECTIVE), 5, events());
    }

    private Cohort pooled(AnalysisVersion analysis) {
        return ReportCalculator.report(RUN, DIGEST, analysis, run.cases(), run.facts())
                .cohorts()
                .getFirst();
    }

    private static Double value(Cohort cohort, String name, Dimensions dimensions) {
        return cohort.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .filter(metric -> metric.dimensions().equals(dimensions))
                .map(Metric::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " " + dimensions + " missing"));
    }

    @Test
    void aReasonConfiguredAtZeroPointsIsCounted() {
        var cohort = pooled(AnalysisVersion.CURRENT);

        assertThat(value(cohort, REASON_METRIC, Dimensions.of(Faction.ERASERS, "ANNIHILATION_WASTED")))
                .isEqualTo(0.4);
    }

    @Test
    void aJoinedRecordCountsEachReasonOneByOneAndNoJoinedKeyIsADimension() {
        var cohort = pooled(AnalysisVersion.CURRENT);

        assertThat(value(cohort, REASON_METRIC, Dimensions.of(Faction.ERASERS, "PARADOX_CASCADE_PENALTY")))
                .isEqualTo(0.4);
        assertThat(cohort.metrics())
                .filteredOn(metric -> metric.name().equals(REASON_METRIC))
                .isNotEmpty()
                .allSatisfy(
                        metric -> assertThat(metric.dimensions().scoreReason()).doesNotContain(","));
    }

    @Test
    void thePlacedBetsThatRecordedNoBetReasonAreTheVoidedRateOverAllSeatEras() {
        var cohort = pooled(AnalysisVersion.CURRENT);

        assertThat(value(cohort, VOIDED_METRIC, Dimensions.of(Faction.REVISIONISTS)))
                .isEqualTo(0.6);
        assertThat(value(cohort, VOIDED_METRIC, Dimensions.of(Faction.WEAVERS))).isEqualTo(0.2);
        assertThat(value(cohort, VOIDED_METRIC, Dimensions.of(Faction.ERASERS))).isZero();
    }

    @Test
    void aWeaversLinkAndItsPlacementShareTheSameEras() {
        var cohort = pooled(AnalysisVersion.CURRENT);

        // One link in five eras and one voided placement in five eras, so the Weaver placed two bets in five.
        assertThat(value(cohort, REASON_METRIC, Dimensions.of(Faction.WEAVERS, "CHAIN_LINK_ADDED")))
                .isEqualTo(0.2);
        assertThat(value(cohort, "bet_win_rate_per_era", Dimensions.of(Faction.WEAVERS)))
                .isEqualTo(0.2);
    }

    @Test
    void version6KeepsEveryVersion5Metric() {
        var current = pooled(AnalysisVersion.CURRENT);
        var previous = pooled(V5);

        assertThat(current.metrics().stream()
                        .filter(metric -> !metric.name().equals(REASON_METRIC)
                                && !metric.name().equals(VOIDED_METRIC)))
                .containsExactlyElementsOf(previous.metrics());
    }

    @Test
    void version5ListsNeitherNewMetric() {
        var cohort = pooled(V5);

        assertThat(cohort.metrics()).extracting(Metric::name).doesNotContain(REASON_METRIC, VOIDED_METRIC);
    }
}
