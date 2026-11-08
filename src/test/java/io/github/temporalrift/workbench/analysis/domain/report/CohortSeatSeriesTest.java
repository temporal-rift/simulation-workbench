package io.github.temporalrift.workbench.analysis.domain.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.statistics.BlockBootstrap;
import io.github.temporalrift.workbench.shared.game.Faction;

class CohortSeatSeriesTest {

    private static final List<Faction> EPW = SyntheticRun.EPW;
    private static final List<Faction> PWE = List.of(Faction.PROPHETS, Faction.WEAVERS, Faction.ERASERS);

    @Test
    void everySeatGivesThePooledSeriesAndOneSeatOnlyThatSeatsGames() {
        var run = new SyntheticRun("base", "faction-greedy-v1");
        for (var seed = 1; seed <= 4; seed++) {
            // Erasers win on seat 0 and lose on seat 2.
            run.win(String.valueOf(seed), EPW, 0).win(String.valueOf(seed), PWE, 0);
        }
        var key = CohortKey.pooledOf(run.cases().getFirst());
        var cohort = CohortComputation.of(
                key,
                run.cases(),
                run.facts(),
                Vocabulary.of(run.cases(), run.facts().values()),
                Set.of(),
                AnalysisVersion.CURRENT);
        var erasers = Dimensions.of(Faction.ERASERS);
        var plan = BlockBootstrap.identity(4);

        var pooled = cohort.series("faction_win_rate", erasers).orElseThrow();
        var everySeat =
                cohort.seatSeries("faction_win_rate", erasers, Set.of(0, 1, 2)).orElseThrow();
        var seatZero = cohort.seatSeries("faction_win_rate", erasers, Set.of(0)).orElseThrow();

        assertThat(everySeat.numerator(plan)).isEqualTo(pooled.numerator(plan)).isEqualTo(4);
        assertThat(everySeat.denominator(plan))
                .isEqualTo(pooled.denominator(plan))
                .isEqualTo(8);
        assertThat(seatZero.numerator(plan)).isEqualTo(4);
        assertThat(seatZero.denominator(plan)).isEqualTo(4);
        assertThat(seatZero.totalNumerator()).isEqualTo(4);
    }
}
