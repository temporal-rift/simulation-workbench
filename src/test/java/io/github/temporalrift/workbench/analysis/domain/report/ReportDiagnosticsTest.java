package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.shared.game.Faction;

class ReportDiagnosticsTest {

    private static final UUID RUN = new UUID(0, 5);
    private static final String DIGEST = "d".repeat(64);

    private final SyntheticRun run = new SyntheticRun("base", "random");

    @Test
    void version3ReportsPointsByReasonWinPathsWinEraAndBetAccuracyPerFaction() {
        run.played(
                        "1",
                        SyntheticRun.EPW,
                        Set.of(0),
                        Map.of(0, WinPath.FACTION_OBJECTIVE),
                        3,
                        List.of(
                                new GameEvent.ScoreRecorded(0, 1, "ANNIHILATED_OUTCOME", 2),
                                new GameEvent.ScoreRecorded(0, 2, "ANNIHILATION_WASTED", 0),
                                new GameEvent.ScoreRecorded(1, 1, "EVENT_RESOLVED_AS_WRITTEN", 2)))
                .played(
                        "2",
                        SyntheticRun.EPW,
                        Set.of(1),
                        Map.of(1, WinPath.SCORE_THRESHOLD),
                        5,
                        List.of(
                                new GameEvent.ScoreRecorded(0, 1, "ANNIHILATED_OUTCOME", 4),
                                new GameEvent.ScoreRecorded(1, 1, "EVENT_RESOLVED_DIFFERENTLY_THAN_WRITTEN", -1)));

        var pooled = ReportCalculator.report(RUN, DIGEST, AnalysisVersion.CURRENT, run.cases(), run.facts())
                .cohorts()
                .getFirst();

        assertThat(value(pooled, "score_by_reason", Dimensions.of(Faction.ERASERS, "ANNIHILATED_OUTCOME")))
                .isEqualTo(3.0);
        assertThat(value(pooled, "win_path_rate", Dimensions.of(Faction.ERASERS, WinPath.FACTION_OBJECTIVE)))
                .isEqualTo(1.0);
        assertThat(value(pooled, "win_path_rate", Dimensions.of(Faction.PROPHETS, WinPath.SCORE_THRESHOLD)))
                .isEqualTo(1.0);
        assertThat(value(pooled, "win_era", Dimensions.of(Faction.ERASERS))).isEqualTo(3.0);
        assertThat(value(pooled, "win_era", Dimensions.of(Faction.PROPHETS))).isEqualTo(5.0);
        assertThat(value(pooled, "bet_success_rate", Dimensions.of(Faction.PROPHETS)))
                .isEqualTo(0.5);
        assertThat(value(pooled, "bet_success_rate", Dimensions.of(Faction.ERASERS)))
                .isCloseTo(2.0 / 3.0, within(1e-6));
    }

    @Test
    void aWinnerWhoseWinPathIsUnknownIsReportedAsUnknownNotAsAMiss() {
        run.played("1", SyntheticRun.EPW, Set.of(0), Map.of(0, WinPath.FACTION_OBJECTIVE), 3, List.of())
                .played("2", SyntheticRun.EPW, Set.of(0), Map.of(), 3, List.of());

        var pooled = ReportCalculator.report(RUN, DIGEST, AnalysisVersion.CURRENT, run.cases(), run.facts())
                .cohorts()
                .getFirst();

        var rate = pooled.metrics().stream()
                .filter(metric -> metric.name().equals("win_path_rate"))
                .filter(metric -> metric.dimensions().equals(Dimensions.of(Faction.ERASERS, WinPath.FACTION_OBJECTIVE)))
                .findFirst()
                .orElseThrow();
        assertThat(rate.value()).isEqualTo(1.0);
        assertThat(rate.denominator()).isEqualTo(1);
        assertThat(rate.unknownCount()).isEqualTo(1);
    }

    @Test
    void version2ReportsNoneOfTheDiagnostics() {
        run.played(
                "1",
                SyntheticRun.EPW,
                Set.of(0),
                Map.of(0, WinPath.FACTION_OBJECTIVE),
                3,
                List.of(new GameEvent.ScoreRecorded(0, 1, "ANNIHILATED_OUTCOME", 2)));

        var pooled = ReportCalculator.report(RUN, DIGEST, new AnalysisVersion("2", 1L), run.cases(), run.facts())
                .cohorts()
                .getFirst();

        assertThat(pooled.metrics())
                .extracting(Metric::name)
                .doesNotContain("score_by_reason", "win_path_rate", "win_era", "bet_success_rate");
    }

    private static Double value(Cohort cohort, String name, Dimensions dimensions) {
        return cohort.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .filter(metric -> metric.dimensions().equals(dimensions))
                .map(Metric::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " " + dimensions + " missing"));
    }
}
