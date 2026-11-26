package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

class ReportWinConditionsTest {

    private static final UUID RUN = new UUID(0, 7);
    private static final String DIGEST = "f".repeat(64);
    private static final List<Faction> SEATS = List.of(Faction.PROPHETS, Faction.WEAVERS, Faction.ERASERS);

    private final SyntheticRun run = new SyntheticRun("base", "random");

    @Test
    void aWinnerThatMetBothConditionsCountsTowardEachAndTowardItsPath() {
        run.played(
                "1",
                SEATS,
                Set.of(0),
                Map.of(0, WinPath.SCORE_THRESHOLD),
                Map.of(0, Set.of(WinPath.SCORE_THRESHOLD, WinPath.FACTION_OBJECTIVE)),
                4);

        var pooled = pooled(AnalysisVersion.CURRENT);

        assertThat(value(pooled, "win_condition_rate", Faction.PROPHETS, WinPath.SCORE_THRESHOLD))
                .isEqualTo(1.0);
        assertThat(value(pooled, "win_condition_rate", Faction.PROPHETS, WinPath.FACTION_OBJECTIVE))
                .isEqualTo(1.0);
        assertThat(value(pooled, "win_path_rate", Faction.PROPHETS, WinPath.SCORE_THRESHOLD))
                .isEqualTo(1.0);
    }

    @Test
    void theSharesOfAFactionAreOverItsWinsOnly() {
        run.played(
                        "1",
                        SEATS,
                        Set.of(0),
                        Map.of(0, WinPath.SCORE_THRESHOLD),
                        Map.of(0, Set.of(WinPath.SCORE_THRESHOLD, WinPath.FACTION_OBJECTIVE)),
                        4)
                .played(
                        "2",
                        SEATS,
                        Set.of(0),
                        Map.of(0, WinPath.SCORE_THRESHOLD),
                        Map.of(0, Set.of(WinPath.SCORE_THRESHOLD)),
                        4)
                .played("3", SEATS, Set.of(1), Map.of(1, WinPath.FACTION_OBJECTIVE), Map.of(), 4);

        var pooled = pooled(AnalysisVersion.CURRENT);

        assertThat(value(pooled, "win_condition_rate", Faction.PROPHETS, WinPath.SCORE_THRESHOLD))
                .isEqualTo(1.0);
        assertThat(value(pooled, "win_condition_rate", Faction.PROPHETS, WinPath.FACTION_OBJECTIVE))
                .isEqualTo(0.5);
    }

    @Test
    void aStabilizedTimelineCountsTowardNoCondition() {
        run.game("1", SEATS, EndReason.TIMELINE_STABILIZED, Set.of(0), 4);

        var pooled = pooled(AnalysisVersion.CURRENT);

        assertThat(value(pooled, "win_condition_rate", Faction.PROPHETS, WinPath.SCORE_THRESHOLD))
                .isZero();
        assertThat(value(pooled, "win_condition_rate", Faction.PROPHETS, WinPath.FACTION_OBJECTIVE))
                .isZero();
    }

    @Test
    void version5KeepsEveryVersion4Metric() {
        run.played(
                "1",
                SEATS,
                Set.of(0),
                Map.of(0, WinPath.SCORE_THRESHOLD),
                Map.of(0, Set.of(WinPath.SCORE_THRESHOLD, WinPath.FACTION_OBJECTIVE)),
                4);

        var current = pooled(new AnalysisVersion("5", 1L));
        var previous = pooled(new AnalysisVersion("4", 1L));

        assertThat(current.metrics().stream().filter(metric -> !metric.name().equals("win_condition_rate")))
                .containsExactlyElementsOf(previous.metrics());
    }

    @Test
    void version4ListsNoWinConditionRate() {
        run.played(
                "1",
                SEATS,
                Set.of(0),
                Map.of(0, WinPath.SCORE_THRESHOLD),
                Map.of(0, Set.of(WinPath.SCORE_THRESHOLD, WinPath.FACTION_OBJECTIVE)),
                4);

        var pooled = pooled(new AnalysisVersion("4", 1L));

        assertThat(pooled.metrics()).isNotEmpty().extracting(Metric::name).doesNotContain("win_condition_rate");
    }

    private Cohort pooled(AnalysisVersion analysis) {
        return ReportCalculator.report(RUN, DIGEST, analysis, run.cases(), run.facts())
                .cohorts()
                .getFirst();
    }

    private static Double value(Cohort cohort, String name, Faction faction, WinPath path) {
        var dimensions = Dimensions.of(faction, path);
        return cohort.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .filter(metric -> metric.dimensions().equals(dimensions))
                .map(Metric::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " " + dimensions + " missing"));
    }
}
