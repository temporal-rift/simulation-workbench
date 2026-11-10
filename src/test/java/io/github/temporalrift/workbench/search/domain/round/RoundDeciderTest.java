package io.github.temporalrift.workbench.search.domain.round;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.log.LogEntry.Decision;
import io.github.temporalrift.workbench.search.domain.log.LogEntry.Reason;
import io.github.temporalrift.workbench.search.domain.round.Assessment.Verdict;
import io.github.temporalrift.workbench.search.domain.round.RoundDecider.StopReason;

class RoundDeciderTest {

    private static final SearchAlgorithm ALGORITHM =
            new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V1, 8, 2, 2, 1);

    private static final SearchAlgorithm VERSION_2 =
            new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2, 4, 2, 2, 1);

    @Test
    void version2KeepsTheCandidatesClosestToTheGatesWhenEveryCandidateFailsOne() {
        var outcome = RoundDecider.decide(
                List.of(
                        failing(0, 3.0, 1.0, "G-05"),
                        failing(1, 0.5, 9.0, "G-05", "G-06"),
                        failing(2, 2.0, 2.0, "G-06"),
                        failing(3, 0.8, 5.0, "G-05")),
                VERSION_2,
                0,
                1);

        assertThat(outcome.survivors())
                .extracting(survivor -> survivor.candidate().id())
                .containsExactly("c-0001", "c-0003");
        var dropped = entryOf(outcome, 0);
        assertThat(dropped.decision()).isEqualTo(Decision.DISCARDED);
        assertThat(dropped.reason()).isEqualTo(Reason.GATE_FAILS);
        assertThat(dropped.evidence().lineIds()).containsExactly("G-05");
        assertThat(dropped.evaluation().gateMiss()).isEqualTo(3.0);
        assertThat(entryOf(outcome, 1).decision()).isEqualTo(Decision.KEPT);
        assertThat(outcome.shortList()).isEmpty();
    }

    @Test
    void version2RanksACandidateThatPassesEveryGateBeforeAnyThatFailsOne() {
        var outcome = RoundDecider.decide(
                List.of(
                        failing(0, 0.1, 1.0, "G-05"),
                        passing(1, 40.0),
                        failing(2, 0.2, 2.0, "G-06"),
                        failing(3, 0.3, 0.5, "G-05")),
                VERSION_2,
                0,
                1);

        assertThat(outcome.survivors())
                .extracting(survivor -> survivor.candidate().id())
                .containsExactly("c-0001", "c-0000");
        assertThat(outcome.shortList()).containsExactly("c-0001");
    }

    @Test
    void version2KeepsHalfTheRoundWhateverItsCandidatesFail() {
        var outcome = RoundDecider.decide(
                List.of(passing(0, 1.0), passing(1, 2.0), invalid(2), failing(3, 0.1, 0.1, "G-01")), VERSION_2, 0, 1);

        assertThat(outcome.survivors())
                .extracting(survivor -> survivor.candidate().id())
                .containsExactly("c-0000", "c-0001");
        assertThat(entryOf(outcome, 2).reason()).isEqualTo(Reason.NOT_IN_TOP_FRACTION);
        assertThat(entryOf(outcome, 3).reason()).isEqualTo(Reason.GATE_FAILS);
    }

    @Test
    void theEndOfAVersion2PlanShortListsOnlyACandidateThatPassesEveryGate() {
        var failingLast = RoundDecider.concludePlan(List.of(failing(5, 0.4, 3.0, "G-05")), VERSION_2, 4, 20);

        assertThat(failingLast).singleElement().satisfies(entry -> {
            assertThat(entry.decision()).isEqualTo(Decision.DISCARDED);
            assertThat(entry.reason()).isEqualTo(Reason.GATES_NOT_MET);
            assertThat(entry.evidence().lineIds()).containsExactly("G-05");
            assertThat(entry.evaluation().gateMiss()).isEqualTo(0.4);
            assertThat(entry.sequence()).isEqualTo(20);
        });

        var passingLast = RoundDecider.concludePlan(List.of(passing(6, 2.0)), VERSION_2, 4, 20);

        assertThat(passingLast).singleElement().satisfies(entry -> {
            assertThat(entry.decision()).isEqualTo(Decision.SHORT_LISTED);
            assertThat(entry.reason()).isNull();
        });
    }

    @Test
    void version2DominanceCountsOnlyAmongCandidatesThatPassEveryGate() {
        var outcome = RoundDecider.decide(
                List.of(passing(0, 1.0, "c-0003"), passing(1, 2.0, "c-0000"), invalid(2), failing(3, 0.1, 0.1, "G-01")),
                VERSION_2,
                0,
                1);

        assertThat(entryOf(outcome, 0).decision()).isEqualTo(Decision.KEPT);
        assertThat(entryOf(outcome, 1).reason()).isEqualTo(Reason.DOMINATED);
        assertThat(entryOf(outcome, 1).evidence().dominatedBy()).isEqualTo("c-0000");
    }

    @Test
    void aFailingGateDiscardsAndEveryFailingLineIsEvidence() {
        var outcome = decide(rejected(0, "G-01", "G-03"), scored(1, 2.0));

        var entry = entryOf(outcome, 0);
        assertThat(entry.decision()).isEqualTo(Decision.DISCARDED);
        assertThat(entry.reason()).isEqualTo(Reason.GATE_FAILS);
        assertThat(entry.evidence().lineIds()).containsExactly("G-01", "G-03");
        assertThat(entry.evaluation().verdict()).isEqualTo("REJECTED");
    }

    @Test
    void aDominatedCandidateIsDiscardedNamingTheFirstCandidateThatDominatesIt() {
        var outcome = decide(scored(0, 1.0), scored(1, 3.0, "c-0002", "c-0000"), scored(2, 2.0));

        var entry = entryOf(outcome, 1);
        assertThat(entry.reason()).isEqualTo(Reason.DOMINATED);
        assertThat(entry.evidence().dominatedBy()).isEqualTo("c-0002");
        assertThat(entry.evidence().lineIds()).isNull();
    }

    @Test
    void theBestShareOfTheSurvivorsIsKeptAndTheRestFallOutsideTheTopFraction() {
        var outcome = decide(scored(0, 5.0), scored(1, 1.0), scored(2, 3.0), scored(3, 2.0));

        assertThat(outcome.entries())
                .extracting(LogEntry::candidateId, LogEntry::decision, LogEntry::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c-0000", Decision.DISCARDED, Reason.NOT_IN_TOP_FRACTION),
                        org.assertj.core.groups.Tuple.tuple("c-0001", Decision.KEPT, null),
                        org.assertj.core.groups.Tuple.tuple("c-0002", Decision.DISCARDED, Reason.NOT_IN_TOP_FRACTION),
                        org.assertj.core.groups.Tuple.tuple("c-0003", Decision.KEPT, null));
        assertThat(outcome.survivors())
                .extracting(assessment -> assessment.candidate().id())
                .containsExactly("c-0001", "c-0003");
    }

    @Test
    void aScoreAloneNeverDiscardsInsideTheTopFractionAndAtLeastOneSurvives() {
        var outcome = decide(scored(0, 100.0));

        assertThat(entryOf(outcome, 0).decision()).isEqualTo(Decision.KEPT);
    }

    @Test
    void anInvalidEvaluationRanksAfterEveryValidOneAndTiesBreakByCandidateId() {
        var outcome = decide(invalid(0), scored(1, 4.0), scored(2, 4.0), scored(3, 9.0));

        assertThat(outcome.survivors())
                .extracting(assessment -> assessment.candidate().id())
                .containsExactly("c-0001", "c-0002");
        assertThat(entryOf(outcome, 0).reason()).isEqualTo(Reason.NOT_IN_TOP_FRACTION);
        assertThat(entryOf(outcome, 0).evaluation().score()).isNull();
    }

    @Test
    void theOutcomeDoesNotDependOnTheOrderTheCandidatesArriveIn() {
        var assessments = List.of(scored(3, 2.0), scored(0, 5.0), rejected(2, "G-01"), scored(1, 1.0));
        var reversed = new java.util.ArrayList<>(assessments);
        Collections.reverse(reversed);

        assertThat(RoundDecider.decide(reversed, ALGORITHM, 0, 10))
                .isEqualTo(RoundDecider.decide(assessments, ALGORITHM, 0, 10));
    }

    @Test
    void entriesAreNumberedInDecisionOrderFromTheGivenSequence() {
        var outcome = decide(scored(0, 1.0), scored(1, 2.0));

        assertThat(outcome.entries()).extracting(LogEntry::sequence).containsExactly(10, 11);
    }

    @Test
    void theShortListIsTheBestSurvivorsUpToItsSize() {
        var algorithm = new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V1, 8, 2, 2, 2);
        var outcome = RoundDecider.decide(
                List.of(scored(0, 4.0), scored(1, 1.0), scored(2, 2.0), scored(3, 3.0)), algorithm, 0, 1);

        assertThat(outcome.shortList()).containsExactly("c-0001", "c-0002");
    }

    @Test
    void aStableShortListEndsTheSearchAndTheOtherSurvivorsFallOutsideTheTopFraction() {
        var survivors = List.of(scored(1, 1.0), scored(2, 2.0));

        var entries = RoundDecider.conclude(survivors, List.of(), ALGORITHM, StopReason.SHORT_LIST_STABLE, 3, 40);

        assertThat(entries)
                .extracting(LogEntry::candidateId, LogEntry::decision, LogEntry::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c-0001", Decision.SHORT_LISTED, null),
                        org.assertj.core.groups.Tuple.tuple("c-0002", Decision.DISCARDED, Reason.NOT_IN_TOP_FRACTION));
        assertThat(entries).extracting(LogEntry::sequence).containsExactly(40, 41);
    }

    @Test
    void anExhaustedBudgetDiscardsEverythingUndecidedIncludingTheRoundInPlay() {
        var survivors = List.of(scored(1, 1.0), scored(2, 2.0));
        var undecided = List.of(unplayed(5), unplayed(4));

        var entries = RoundDecider.conclude(survivors, undecided, ALGORITHM, StopReason.BUDGET_EXHAUSTED, 3, 40);

        assertThat(entries)
                .extracting(LogEntry::candidateId, LogEntry::decision, LogEntry::reason)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("c-0001", Decision.SHORT_LISTED, null),
                        org.assertj.core.groups.Tuple.tuple("c-0002", Decision.DISCARDED, Reason.BUDGET_EXHAUSTED),
                        org.assertj.core.groups.Tuple.tuple("c-0004", Decision.DISCARDED, Reason.BUDGET_EXHAUSTED),
                        org.assertj.core.groups.Tuple.tuple("c-0005", Decision.DISCARDED, Reason.BUDGET_EXHAUSTED));
        assertThat(entries.get(2).evaluation()).isNull();
    }

    @Test
    void theShortListIsStableOnlyAfterItStayedTheSameThroughTwoRounds() {
        var a = List.of("c-0001");
        var b = List.of("c-0002");

        assertThat(RoundDecider.stable(List.of(a))).isFalse();
        assertThat(RoundDecider.stable(List.of(a, a))).isFalse();
        assertThat(RoundDecider.stable(List.of(a, a, a))).isTrue();
        assertThat(RoundDecider.stable(List.of(b, a, a))).isFalse();
        assertThat(RoundDecider.stable(List.of(b, b, a, a, a))).isTrue();
        assertThat(RoundDecider.stable(List.of(a, a, b, a, a))).isFalse();
    }

    @Test
    void anEntryIsCanonicalJsonWithAFixedKeyOrder() {
        var entry = new LogEntry(
                3,
                0,
                "c-0007",
                new TreeMap<>(java.util.Map.of("b", new BigDecimal("1.50"), "a", new BigDecimal("2"))),
                "d".repeat(64),
                List.of("11", "29"),
                8,
                2,
                new LogEntry.Evaluation("REJECTED", null),
                Decision.DISCARDED,
                Reason.GATE_FAILS,
                LogEntry.Evidence.failingLines(List.of("G-01")));

        assertThat(entry.canonicalJson())
                .isEqualTo("{\"sequence\":3,\"round\":0,\"candidateId\":\"c-0007\","
                        + "\"overrides\":{\"a\":\"2\",\"b\":\"1.5\"},\"effectiveRulesDigest\":\"" + "d".repeat(64)
                        + "\",\"seeds\":[\"11\",\"29\"],\"gamesSpent\":8,\"redraws\":2,"
                        + "\"evaluation\":{\"verdict\":\"REJECTED\",\"score\":null},\"decision\":\"DISCARDED\","
                        + "\"reason\":\"GATE_FAILS\",\"evidence\":{\"lineIds\":[\"G-01\"]}}");
    }

    private static RoundDecider.Outcome decide(Assessment... assessments) {
        return RoundDecider.decide(List.of(assessments), ALGORITHM, 0, 10);
    }

    private static LogEntry entryOf(RoundDecider.Outcome outcome, int index) {
        return outcome.entries().stream()
                .filter(entry -> entry.candidateId().equals(Candidate.idOf(index)))
                .findFirst()
                .orElseThrow();
    }

    private static Assessment scored(int index, double score, String... dominators) {
        return assessment(index, Verdict.UNCONFIRMED, score, List.of(), List.of(dominators), 4);
    }

    private static Assessment invalid(int index) {
        return assessment(index, Verdict.INVALID, null, List.of(), List.of(), 4);
    }

    private static Assessment rejected(int index, String... lineIds) {
        return assessment(index, Verdict.REJECTED, 9.0, List.of(lineIds), List.of(), 4);
    }

    private static Assessment unplayed(int index) {
        return assessment(index, Verdict.UNCONFIRMED, null, List.of(), List.of(), 0);
    }

    /** A version 2 candidate that passes every gate. */
    private static Assessment passing(int index, double score, String... dominators) {
        return new Assessment(
                candidate(index),
                List.of("1", "2"),
                4,
                Verdict.UNCONFIRMED,
                score,
                List.of(),
                0.0,
                List.of(dominators));
    }

    /** A version 2 candidate that fails the given gate lines and misses the gates by {@code gateMiss}. */
    private static Assessment failing(int index, double gateMiss, double score, String... lineIds) {
        return new Assessment(
                candidate(index), List.of("1", "2"), 4, Verdict.REJECTED, score, List.of(lineIds), gateMiss, List.of());
    }

    private static Assessment assessment(
            int index, Verdict verdict, Double score, List<String> lines, List<String> dominators, int games) {
        return new Assessment(candidate(index), List.of("1", "2"), games, verdict, score, lines, null, dominators);
    }

    private static Candidate candidate(int index) {
        return new Candidate(Candidate.idOf(index), 0, List.of(index), new TreeMap<>(), "r".repeat(63) + index, 0);
    }
}
