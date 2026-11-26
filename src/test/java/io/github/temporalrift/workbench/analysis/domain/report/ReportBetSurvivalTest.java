package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.WinPath;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class ReportBetSurvivalTest {

    private static final UUID RUN = new UUID(0, 6);
    private static final String DIGEST = "e".repeat(64);
    private static final List<Faction> SEATS = List.of(Faction.REVISIONISTS, Faction.WEAVERS, Faction.ERASERS);

    private final SyntheticRun run = new SyntheticRun("base", "random");

    @Test
    void version4CountsBetsPlacedScoredAndWonPerFaction() {
        run.played("1", SEATS, Set.of(0), Map.of(0, WinPath.FACTION_OBJECTIVE), 5, events());

        var pooled = pooled(new AnalysisVersion("4", 1L));

        assertThat(value(pooled, "bets_placed", Faction.REVISIONISTS)).isEqualTo(5.0);
        assertThat(value(pooled, "bets_scored", Faction.REVISIONISTS)).isEqualTo(3.0);
        assertThat(value(pooled, "bet_survival_rate", Faction.REVISIONISTS)).isEqualTo(0.6);
        assertThat(value(pooled, "bet_win_rate_per_era", Faction.REVISIONISTS)).isEqualTo(0.4);
        assertThat(value(pooled, "bets_placed", Faction.WEAVERS)).isEqualTo(1.0);
        assertThat(value(pooled, "bet_survival_rate", Faction.WEAVERS)).isEqualTo(1.0);
        assertThat(value(pooled, "bet_win_rate_per_era", Faction.WEAVERS)).isEqualTo(0.2);
        assertThat(value(pooled, "bets_placed", Faction.ERASERS)).isEqualTo(1.0);
        assertThat(value(pooled, "bets_scored", Faction.ERASERS)).isEqualTo(1.0);
        assertThat(value(pooled, "bet_survival_rate", Faction.ERASERS)).isEqualTo(1.0);
        assertThat(value(pooled, "bet_win_rate_per_era", Faction.ERASERS)).isZero();
    }

    @Test
    void version4KeepsEveryVersion3Metric() {
        run.played("1", SEATS, Set.of(0), Map.of(0, WinPath.FACTION_OBJECTIVE), 5, events());

        var current = pooled(new AnalysisVersion("4", 1L));
        var previous = pooled(new AnalysisVersion("3", 1L));

        var added = List.of("bets_placed", "bets_scored", "bet_survival_rate", "bet_win_rate_per_era");
        assertThat(current.metrics().stream().filter(metric -> !added.contains(metric.name())))
                .containsExactlyElementsOf(previous.metrics());
    }

    @Test
    void version3ListsNoneOfTheBetSurvivalMetrics() {
        run.played("1", SEATS, Set.of(0), Map.of(0, WinPath.FACTION_OBJECTIVE), 5, events());

        var pooled = pooled(new AnalysisVersion("3", 1L));

        assertThat(pooled.metrics())
                .extracting(Metric::name)
                .contains("bet_success_rate")
                .doesNotContain("bets_placed", "bets_scored", "bet_survival_rate", "bet_win_rate_per_era");
    }

    private static List<GameEvent> events() {
        Stream<GameEvent> rewrites =
                IntStream.rangeClosed(1, 5).mapToObj(era -> new GameEvent.SpecialPlayed(0, era, SpecialAction.REWRITE));
        return Stream.concat(
                        rewrites,
                        Stream.of(
                                new GameEvent.ScoreRecorded(0, 1, "SECRET_OUTCOME_WON", 5),
                                new GameEvent.ScoreRecorded(0, 2, "SECRET_OUTCOME_WON", 5),
                                new GameEvent.ScoreRecorded(0, 3, "SECRET_OUTCOME_MISSED", -2),
                                new GameEvent.SpecialPlayed(1, 1, SpecialAction.THREAD),
                                new GameEvent.ScoreRecorded(1, 1, "CHAIN_LINK_ADDED,PARADOX_STABILIZED", 6),
                                new GameEvent.SpecialPlayed(2, 1, SpecialAction.ANNIHILATE),
                                new GameEvent.ScoreRecorded(2, 1, "ANNIHILATION_WASTED", 0)))
                .toList();
    }

    private Cohort pooled(AnalysisVersion analysis) {
        return ReportCalculator.report(RUN, DIGEST, analysis, run.cases(), run.facts())
                .cohorts()
                .getFirst();
    }

    private static Double value(Cohort cohort, String name, Faction faction) {
        var dimensions = Dimensions.of(faction);
        return cohort.metrics().stream()
                .filter(metric -> metric.name().equals(name))
                .filter(metric -> metric.dimensions().equals(dimensions))
                .map(Metric::value)
                .findFirst()
                .orElseThrow(() -> new AssertionError(name + " " + dimensions + " missing"));
    }
}
