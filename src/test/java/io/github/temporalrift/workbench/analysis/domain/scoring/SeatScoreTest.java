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

    private static List<Long> totals(FactionScoring engine, BetOutcome... outcomes) {
        var score = new SeatScore(engine);
        return java.util.Arrays.stream(outcomes)
                .map(score::apply)
                .map(units -> units / SeatScore.UNITS)
                .toList();
    }

    @Test
    void aMissResetsTheWinRunWhenItIsListed() {
        assertThat(totals(engine(Set.of(MISSED), null, 0), WON, WON, MISSED, WON))
                .containsExactly(2L, 6L, 6L, 8L);
    }

    @Test
    void theWinRunSurvivesAMissWhenNothingResets() {
        assertThat(totals(engine(Set.of(), null, 0), WON, WON, MISSED, WON)).containsExactly(2L, 6L, 6L, 12L);
    }

    @Test
    void theLastEntryRepeatsPastTheEndOfTheWinPoints() {
        assertThat(totals(engine(Set.of(), null, 0), WON, WON, WON, WON, WON)).containsExactly(2L, 6L, 12L, 18L, 24L);
    }

    @Test
    void aMissScoresItsPointsAndNeverTakesBankedPointsAway() {
        var costly = new FactionScoring(Faction.WEAVERS, List.of(5), -2, Set.of(MISSED), null, 0);
        assertThat(totals(costly, WON, MISSED, VOIDED)).containsExactly(5L, 3L, 3L);
    }

    @Test
    void theObjectiveBonusIsAddedOnceOnTheConfiguredWin() {
        assertThat(totals(engine(Set.of(), 2, 10), WON, WON, WON)).containsExactly(2L, 16L, 22L);
    }

    @Test
    void aVoidedBetResetsOnlyWhenListed() {
        assertThat(totals(engine(Set.of(VOIDED), null, 0), WON, VOIDED, WON)).containsExactly(2L, 2L, 4L);
        assertThat(totals(engine(Set.of(), null, 0), WON, VOIDED, WON)).containsExactly(2L, 2L, 6L);
    }

    @Test
    void decimalExtrasAddUpExactlyWhateverTheOrder() {
        var tenths = new SeatScore(engine(Set.of(), null, 0));
        var other = new SeatScore(engine(Set.of(), null, 0));
        for (var era = 0; era < 10; era++) {
            tenths.addExtra(SeatScore.toUnits(0.1));
        }
        other.addExtra(SeatScore.toUnits(0.3));
        other.addExtra(SeatScore.toUnits(0.7));

        assertThat(tenths.total()).isEqualTo(SeatScore.UNITS).isEqualTo(other.total());
    }
}
