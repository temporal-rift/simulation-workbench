package io.github.temporalrift.workbench.analysis.domain.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SeatScoreTest {

    private static final long UNITS = SeatScore.UNITS;

    private static final Escalation WEAVER_LINK =
            new Escalation("weaver-link", List.of("CHAIN_LINK_ADDED"), 6, 3, Set.of(BetOutcome.VOIDED));
    private static final Escalation ACTIVIST_STREAK = new Escalation(
            "activist-streak",
            List.of("DECLARED_OUTCOME_WON", "DECLARED_OUTCOME_WON_WITH_RALLY"),
            6,
            2,
            Set.of(BetOutcome.MISSED, BetOutcome.VOIDED, BetOutcome.NOT_PLACED));

    private static final ScoringEngine DESIGN = new ScoringEngine(
            5,
            List.of(
                    new ReasonPoints("CHAIN_LINK_MISSED", 0),
                    new ReasonPoints("CHAIN_COMPLETED", 0),
                    new ReasonPoints("DECLARED_OUTCOME_MISSED_WITH_RALLY", -4),
                    new ReasonPoints("DECLARED_OUTCOME_MISSED", -2),
                    new ReasonPoints("EVENT_RESOLVED_AS_WRITTEN", 7),
                    new ReasonPoints("FULFILLMENT_SUCCEEDED", 14)),
            List.of(WEAVER_LINK, ACTIVIST_STREAK));

    private final SeatScore seat = new SeatScore(new SeatScore.Pricing(DESIGN));

    /** Scores one era and returns the points it added, in whole points. */
    private long era(BetOutcome outcome, String... reasons) {
        var before = seat.total();
        for (var reason : reasons) {
            seat.occurrence(reason);
        }
        seat.endEra(outcome);
        return (seat.total() - before) / UNITS;
    }

    @Test
    void aWeaverLinkRunGrowsKeepsThroughAMissAndResetsOnAVoidedBet() {
        var scored = new ArrayList<Long>();
        scored.add(era(BetOutcome.WON, "CHAIN_LINK_ADDED"));
        scored.add(era(BetOutcome.WON, "CHAIN_LINK_ADDED"));
        scored.add(era(BetOutcome.MISSED, "CHAIN_LINK_MISSED"));
        scored.add(era(BetOutcome.WON, "CHAIN_LINK_ADDED"));
        scored.add(era(BetOutcome.VOIDED));
        scored.add(era(BetOutcome.WON, "CHAIN_LINK_ADDED"));

        assertThat(scored).containsExactly(6L, 9L, 0L, 12L, 0L, 6L);
    }

    @Test
    void anActivistStreakResetsOnAMissAndScoresTheRallyMissApart() {
        var scored = new ArrayList<Long>();
        scored.add(era(BetOutcome.WON, "DECLARED_OUTCOME_WON"));
        scored.add(era(BetOutcome.WON, "DECLARED_OUTCOME_WON_WITH_RALLY"));
        scored.add(era(BetOutcome.MISSED, "DECLARED_OUTCOME_MISSED_WITH_RALLY"));
        scored.add(era(BetOutcome.WON, "DECLARED_OUTCOME_WON"));

        assertThat(scored).containsExactly(6L, 8L, -4L, 6L);
    }

    @Test
    void aSeatEraWithoutABetResetsTheActivistStreak() {
        era(BetOutcome.WON, "DECLARED_OUTCOME_WON");
        era(BetOutcome.NOT_PLACED);

        assertThat(era(BetOutcome.WON, "DECLARED_OUTCOME_WON")).isEqualTo(6);
    }

    @Test
    void aProphetFulfillmentAndAnAsWrittenResolutionAreScoredApart() {
        assertThat(era(BetOutcome.WON, "FULFILLMENT_SUCCEEDED")).isEqualTo(14);
        assertThat(era(BetOutcome.WON, "EVENT_RESOLVED_AS_WRITTEN")).isEqualTo(7);
    }

    @Test
    void aReasonPricedAtZeroAddsZeroWhateverTheVariantPaid() {
        assertThat(era(BetOutcome.WON, "CHAIN_LINK_ADDED", "CHAIN_COMPLETED")).isEqualTo(6);
    }

    @Test
    void everyOccurrenceOfAReasonInOneEraIsScoredOneByOne() {
        assertThat(era(BetOutcome.WON, "CHAIN_LINK_ADDED", "CHAIN_LINK_ADDED")).isEqualTo(6 + 9);
    }

    @Test
    void anUnpricedReasonIsNotCountedAsZero() {
        assertThatThrownBy(() -> seat.occurrence("MIMIC_NEVER_TRACED"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MIMIC_NEVER_TRACED");
    }

    @Test
    void decimalPointsStayExactInMillionths() {
        seat.addUnits(SeatScore.toUnits(0.1));
        seat.addUnits(SeatScore.toUnits(0.2));

        assertThat(seat.total()).isEqualTo(SeatScore.toUnits(0.3));
    }
}
