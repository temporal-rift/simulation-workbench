package io.github.temporalrift.workbench.analysis.domain.scoring;

import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.MISSED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.NOT_PLACED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.VOIDED;
import static io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome.WON;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

class RescorerTest {

    private static final int FINAL_ERA = 5;
    private static final List<Faction> SEATS = List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);
    private static final RecordedGame.Era LINK = new RecordedGame.Era(WON, List.of("CHAIN_LINK_ADDED"));
    private static final RecordedGame.Era MISS = new RecordedGame.Era(MISSED, List.of("CHAIN_LINK_MISSED"));
    private static final RecordedGame.Era VOID = new RecordedGame.Era(VOIDED, List.of());
    private static final RecordedGame.Era IDLE = new RecordedGame.Era(NOT_PLACED, List.of());
    /** The Weaver of the contract: links in eras 1, 2 and 4, a miss in era 3 and a voided bet in era 5. */
    private static final List<RecordedGame.Era> WEAVER = List.of(LINK, LINK, MISS, LINK, VOID);

    private static ScoringEngine engine(int maxEras) {
        return new ScoringEngine(
                maxEras,
                List.of(new ReasonPoints("CHAIN_LINK_MISSED", 0)),
                List.of(new Escalation("weaver-link", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(VOIDED))));
    }

    private static RecordedGame game(int eras, EndReason endReason, List<RecordedGame.Era> weaver) {
        var seats = new ArrayList<List<RecordedGame.Era>>();
        seats.add(weaver.stream().limit(eras).toList());
        for (var seat = 1; seat < SEATS.size(); seat++) {
            seats.add(Collections.nCopies(eras, IDLE));
        }
        return new RecordedGame(3, "7", SEATS, endReason, eras, seats);
    }

    private static Rescorer.Verdict.Judged judged(Rescorer.Verdict verdict) {
        assertThat(verdict).isInstanceOf(Rescorer.Verdict.Judged.class);
        return (Rescorer.Verdict.Judged) verdict;
    }

    @Test
    void theRaceIsJudgedAfterTheEnginesLastEraByHighestTotal() {
        var verdict = judged(new Rescorer(engine(5)).judge(game(5, EndReason.TIMELINE_STABILIZED, WEAVER), FINAL_ERA));

        assertThat(verdict.era()).isEqualTo(5);
        assertThat(verdict.shares()).containsExactly(Rescorer.SHARE_UNITS, 0, 0);
    }

    @Test
    void equalTotalsShareTheWin() {
        var nobodyScores = game(5, EndReason.TIMELINE_STABILIZED, Collections.nCopies(5, IDLE));

        var verdict = judged(new Rescorer(engine(5)).judge(nobodyScores, FINAL_ERA));

        assertThat(verdict.shares()).containsExactly(20, 20, 20);
    }

    @Test
    void aCaseWhoseRaceOutlastsAnEarlyNormalVictoryIsTruncated() {
        var recorded = game(3, EndReason.WIN_CONDITION_MET, WEAVER);

        assertThat(new Rescorer(engine(5)).judge(recorded, FINAL_ERA)).isInstanceOf(Rescorer.Verdict.Truncated.class);
    }

    @Test
    void aNormalVictoryInTheFinalEraIsJudgedThereByHighestTotal() {
        var recorded = game(5, EndReason.WIN_CONDITION_MET, WEAVER);

        var verdict = judged(new Rescorer(engine(5)).judge(recorded, FINAL_ERA));

        assertThat(verdict.era()).isEqualTo(5);
        assertThat(verdict.shares()).containsExactly(Rescorer.SHARE_UNITS, 0, 0);
    }

    @Test
    void aCollapseIsJudgedAtItsEraByHighestTotal() {
        var recorded = game(3, EndReason.TIMELINE_COLLAPSED, WEAVER);

        assertThat(judged(new Rescorer(engine(5)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(3);
    }

    @Test
    void anEngineThatEndsTheRaceBeforeTheRecordedEndJudgesAtItsOwnLastEra() {
        var recorded = game(5, EndReason.TIMELINE_STABILIZED, WEAVER);

        assertThat(judged(new Rescorer(engine(2)).judge(recorded, FINAL_ERA)).era())
                .isEqualTo(2);
    }

    @Test
    void theRunOfAnEscalationDecidesWhoWins() {
        var recorded = game(5, EndReason.TIMELINE_STABILIZED, WEAVER);
        var seats = new ArrayList<>(recorded.seats());
        seats.set(2, Collections.nCopies(5, new RecordedGame.Era(WON, List.of("PROPHET_POINT"))));
        var withProphet = new RecordedGame(3, "7", SEATS, EndReason.TIMELINE_STABILIZED, 5, seats);
        var flat = new ScoringEngine(
                5,
                List.of(
                        new ReasonPoints("CHAIN_LINK_ADDED", 1),
                        new ReasonPoints("CHAIN_LINK_MISSED", 0),
                        new ReasonPoints("PROPHET_POINT", 2)),
                List.of());
        var escalated = new ScoringEngine(
                5,
                List.of(new ReasonPoints("CHAIN_LINK_MISSED", 0), new ReasonPoints("PROPHET_POINT", 2)),
                List.of(new Escalation("weaver-link", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(VOIDED))));

        // Flat, the Weaver's three links are 3 points against the Prophet's 10; escalated they are 6 + 9 + 12.
        assertThat(judged(new Rescorer(flat).judge(withProphet, FINAL_ERA)).shares())
                .containsExactly(0, 0, Rescorer.SHARE_UNITS);
        assertThat(judged(new Rescorer(escalated).judge(withProphet, FINAL_ERA)).shares())
                .containsExactly(Rescorer.SHARE_UNITS, 0, 0);
    }

    @Test
    void aRecordedReasonTheEngineDoesNotPriceCannotBeScored() {
        var unpriced = new ScoringEngine(5, List.of(new ReasonPoints("CHAIN_LINK_MISSED", 0)), List.of());
        var rescorer = new Rescorer(unpriced);
        var recorded = game(5, EndReason.TIMELINE_STABILIZED, WEAVER);

        assertThatThrownBy(() -> rescorer.judge(recorded, FINAL_ERA))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CHAIN_LINK_ADDED");
    }
}
