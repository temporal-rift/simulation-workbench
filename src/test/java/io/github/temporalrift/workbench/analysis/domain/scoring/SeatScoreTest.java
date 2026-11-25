package io.github.temporalrift.workbench.analysis.domain.scoring;

import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.MISSED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.VOIDED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.WON;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.Faction;

class SeatScoreTest {

    private static FactionScoring engine(Set<BetOutcome> resetOn, Integer objectiveWins, int bonus) {
        return new FactionScoring(Faction.WEAVERS, List.of(2, 4, 6), 0, resetOn, objectiveWins, bonus);
    }

    private static List<Double> totals(FactionScoring engine, BetOutcome... outcomes) {
        var score = new SeatScore(engine);
        return java.util.Arrays.stream(outcomes).map(score::apply).toList();
    }

    @Test
    void aMissResetsTheWinRunWhenItIsListed() {
        assertThat(totals(engine(Set.of(MISSED), null, 0), WON, WON, MISSED, WON))
                .containsExactly(2.0, 6.0, 6.0, 8.0);
    }

    @Test
    void theWinRunSurvivesAMissWhenNothingResets() {
        assertThat(totals(engine(Set.of(), null, 0), WON, WON, MISSED, WON)).containsExactly(2.0, 6.0, 6.0, 12.0);
    }

    @Test
    void theLastEntryRepeatsPastTheEndOfTheWinPoints() {
        assertThat(totals(engine(Set.of(), null, 0), WON, WON, WON, WON, WON))
                .containsExactly(2.0, 6.0, 12.0, 18.0, 24.0);
    }

    @Test
    void aMissScoresItsPointsAndNeverTakesBankedPointsAway() {
        var costly = new FactionScoring(Faction.WEAVERS, List.of(5), -2, Set.of(MISSED), null, 0);
        assertThat(totals(costly, WON, MISSED, VOIDED)).containsExactly(5.0, 3.0, 3.0);
    }

    @Test
    void theObjectiveBonusIsAddedOnceOnTheConfiguredWin() {
        assertThat(totals(engine(Set.of(), 2, 10), WON, WON, WON)).containsExactly(2.0, 16.0, 22.0);
    }

    @Test
    void aVoidedBetResetsOnlyWhenListed() {
        assertThat(totals(engine(Set.of(VOIDED), null, 0), WON, VOIDED, WON)).containsExactly(2.0, 2.0, 4.0);
        assertThat(totals(engine(Set.of(), null, 0), WON, VOIDED, WON)).containsExactly(2.0, 2.0, 6.0);
    }
}
