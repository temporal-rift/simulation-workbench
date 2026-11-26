package io.github.temporalrift.workbench.analysis.domain.scoring;

import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.MISSED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.NOT_PLACED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.WON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

class RescorerTest {

    private static final int FINAL_ERA = 5;
    private static final List<Faction> SEATS = List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);
    /** The Weaver of the issue: wins on eras 1, 2 and 4, a miss on era 3 and no bet on era 5. */
    private static final List<BetOutcome> WEAVER = List.of(WON, WON, MISSED, WON, NOT_PLACED);

    private static ScoringEngine engine(int threshold, int maxEras) {
        return new ScoringEngine(
                threshold,
                maxEras,
                List.of(
                        new FactionScoring(Faction.WEAVERS, List.of(2, 4, 6), -1, Set.of(MISSED), null, 0),
                        new FactionScoring(Faction.ERASERS, List.of(1), 0, Set.of(), null, 0),
                        new FactionScoring(Faction.PROPHETS, List.of(1), 0, Set.of(), null, 0)));
    }

    private static RecordedGame game(int eras, EndReason endReason, List<BetOutcome> weaver) {
        var seats = new ArrayList<List<RecordedGame.Era>>();
        seats.add(weaver.stream()
                .limit(eras)
                .map(outcome -> new RecordedGame.Era(outcome, 0))
                .toList());
        for (var seat = 1; seat < SEATS.size(); seat++) {
            seats.add(java.util.Collections.nCopies(eras, new RecordedGame.Era(NOT_PLACED, 0)));
        }
        return new RecordedGame(3, "7", SEATS, endReason, eras, seats);
    }

    private static Rescorer.Verdict.Judged judged(Rescorer.Verdict verdict) {
        assertThat(verdict).isInstanceOf(Rescorer.Verdict.Judged.class);
        return (Rescorer.Verdict.Judged) verdict;
    }

    @Test
    void theWeaverTotalsAre2_6_5_7_7() {
        var recorded = game(5, EndReason.TIMELINE_STABILIZED, WEAVER);
        // The race ends in the first era whose total reaches the threshold, so each threshold reads one total.
        assertThat(judged(new Rescorer(engine(2, 5)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(1);
        assertThat(judged(new Rescorer(engine(6, 5)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(2);
        assertThat(judged(new Rescorer(engine(7, 5)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(4);
        assertThat(judged(new Rescorer(engine(8, 5)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(5);
    }

    @Test
    void theWinnerOfTheRaceTakesTheWholeShare() {
        var verdict =
                judged(new Rescorer(engine(7, 5)).judge(game(5, EndReason.TIMELINE_STABILIZED, WEAVER), FINAL_ERA));

        assertThat(verdict.shares()).containsExactly(Rescorer.SHARE_UNITS, 0, 0);
    }

    @Test
    void equalTotalsShareTheWin() {
        var nobodyScores = game(5, EndReason.TIMELINE_STABILIZED, java.util.Collections.nCopies(5, NOT_PLACED));

        var verdict = judged(new Rescorer(engine(50, 5)).judge(nobodyScores, FINAL_ERA));

        assertThat(verdict.shares()).containsExactly(20, 20, 20);
    }

    @Test
    void aCaseWhoseRaceOutlastsAnEarlyNormalVictoryIsTruncated() {
        var recorded = game(3, EndReason.WIN_CONDITION_MET, WEAVER);

        assertThat(new Rescorer(engine(50, 5)).judge(recorded, FINAL_ERA))
                .isInstanceOf(Rescorer.Verdict.Truncated.class);
    }

    @Test
    void aThresholdReachedBeforeTheRecordedEndJudgesThereEvenForAnEarlyNormalVictory() {
        var recorded = game(3, EndReason.WIN_CONDITION_MET, WEAVER);

        assertThat(judged(new Rescorer(engine(6, 5)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(2);
    }

    @Test
    void aNormalVictoryInTheFinalEraIsJudgedThereByHighestTotal() {
        var recorded = game(5, EndReason.WIN_CONDITION_MET, WEAVER);

        var verdict = judged(new Rescorer(engine(50, 5)).judge(recorded, FINAL_ERA));

        assertThat(verdict.era()).isEqualTo(5);
        assertThat(verdict.shares()).containsExactly(Rescorer.SHARE_UNITS, 0, 0);
    }

    @Test
    void aCollapseIsJudgedAtItsEraByHighestTotal() {
        var recorded = game(3, EndReason.TIMELINE_COLLAPSED, WEAVER);

        assertThat(judged(new Rescorer(engine(50, 5)).judge(recorded, FINAL_ERA))
                        .era())
                .isEqualTo(3);
    }

    @Test
    void anEngineThatEndsTheRaceBeforeTheRecordedEndJudgesAtItsOwnLastEra() {
        var recorded = game(5, EndReason.TIMELINE_STABILIZED, WEAVER);

        assertThat(judged(new Rescorer(engine(50, 2)).judge(recorded, FINAL_ERA))
                        .era())
                .isEqualTo(2);
    }

    @Test
    void anEngineWithoutASeatedFactionCannotScoreTheCase() {
        var weaversOnly = new ScoringEngine(
                10, 5, List.of(new FactionScoring(Faction.WEAVERS, List.of(2), 0, Set.of(), null, 0)));

        var rescorer = new Rescorer(weaversOnly);
        var recorded = game(5, EndReason.TIMELINE_STABILIZED, WEAVER);

        assertThatThrownBy(() -> rescorer.judge(recorded, FINAL_ERA)).isInstanceOf(IllegalArgumentException.class);
    }
}
