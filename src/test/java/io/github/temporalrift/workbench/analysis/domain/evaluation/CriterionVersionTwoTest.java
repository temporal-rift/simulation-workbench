package io.github.temporalrift.workbench.analysis.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/** The rules criterion version 2 adds: the random-seat G-07, the exact share bound and the mixed confirmation table. */
class CriterionVersionTwoTest {

    private static final UUID RUN = new UUID(0, 11);
    private static final String GREEDY = "faction-greedy-v1";
    private static final String RANDOM = "random-v1";
    private static final String MIXED = "random-seat-vs-faction-greedy-v1";
    private static final List<String> MIXED_SEATS = List.of(RANDOM, GREEDY, GREEDY, GREEDY, GREEDY);
    private static final List<Faction> ALL = List.of(Faction.values()).stream()
            .sorted(Comparator.comparing(Faction::name))
            .toList();

    @Test
    void aFactionOnTheRandomSeatIsMeasuredAgainstTheWholeTable() {
        var run = new SyntheticRun("base", GREEDY).table(MIXED, MIXED_SEATS);
        // Five players, every rotation on 104 seeds: one winner per game, so R is 520 / 2,600 = 0.2. ERASERS sit on the
        // random seat once per seed and win there on 30 seeds; every other game is won by seat 1.
        for (var seed = 1; seed <= 104; seed++) {
            for (var shift = 0; shift < 5; shift++) {
                var seats = rotation(ALL, shift);
                var erasersOnRandomSeat = seats.getFirst() == Faction.ERASERS;
                run.win(String.valueOf(seed), seats, erasersOnRandomSeat && seed <= 30 ? 0 : 1);
            }
        }

        var evaluation = evaluate(TestCriteria.v2(), run, EvaluationMode.CONFIRMATION);

        var erasers = only(evaluation, "G-07", Faction.ERASERS, 5);
        assertThat(erasers.tableId()).isEqualTo(MIXED);
        assertThat(erasers.policyId()).isEqualTo(RANDOM);
        assertThat(erasers.value()).isCloseTo((30.0 / 104) / 0.2, within(1e-4));
        assertThat(erasers.value()).isCloseTo(1.44, within(0.005));
        assertThat(erasers.verdict()).isIn(LineVerdict.FAILS, LineVerdict.INCONCLUSIVE);
        assertThat(only(evaluation, "G-07", Faction.PROPHETS, 5).value()).isZero();
    }

    @Test
    void noCountingFailureInTenGamesIsInconclusiveUnderVersionTwoAndMeetsUnderVersionOne() {
        var run = greedyGames(2, 5);

        var two = only(evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING), "G-01", null, 5);
        var one = only(evaluate(TestCriteria.v1(), run, EvaluationMode.SCREENING), "G-01", null, 5);

        assertThat(two.value()).isZero();
        assertThat(two.interval().lower()).isZero();
        assertThat(two.interval().upper()).isCloseTo(0.3085, within(5e-5));
        assertThat(two.verdict()).isEqualTo(LineVerdict.INCONCLUSIVE);
        assertThat(one.interval()).isEqualTo(new Interval(0, 0));
        assertThat(one.verdict()).isEqualTo(LineVerdict.MEETS);
    }

    @Test
    void noCountingFailureInFiveHundredTwentyGamesMeets() {
        var run = greedyGames(104, 5);

        var g01 = only(evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING), "G-01", null, 5);

        assertThat(g01.interval().upper()).isCloseTo(0.0071, within(5e-5));
        assertThat(g01.verdict()).isEqualTo(LineVerdict.MEETS);
    }

    @Test
    void aLimitZeroLineKeepsTheBootstrapRule() {
        var run = new SyntheticRun("base", GREEDY);
        for (var seed = 1; seed <= 104; seed++) {
            for (var shift = 0; shift < 5; shift++) {
                var seats = rotation(ALL, shift);
                if (seed == 1 && shift == 0) {
                    run.game(String.valueOf(seed), seats, EndReason.RESOLUTION_FAILED, Set.of(), 3);
                } else {
                    run.win(String.valueOf(seed), seats, 0);
                }
            }
        }

        var g02 = only(evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING), "G-02", null, 5);

        assertThat(g02.value()).isCloseTo(1.0 / 520, within(1e-4));
        assertThat(g02.interval().lower()).isZero();
        assertThat(g02.verdict()).isEqualTo(LineVerdict.INCONCLUSIVE);
    }

    @Test
    void aFailedCaseCountsInTheExactBoundOfTheFailureShare() {
        var run = greedyGames(2, 5);
        run.failed("1", rotation(ALL, 0), FailureCode.RUNNER_TIMEOUT);

        var g01 = only(evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING), "G-01", null, 5);

        // 1 failure in 11 finished cases: the exact bound is the 0.975 quantile of Beta(2, 10).
        assertThat(g01.value()).isCloseTo(1.0 / 11, within(1e-4));
        assertThat(g01.interval().upper()).isCloseTo(0.4128, within(5e-4));
    }

    @Test
    void screeningUnderVersionTwoHasNoRandomSeatDataAndNeverRejectsForIt() {
        var run = new SyntheticRun("base", GREEDY);
        for (var policy : List.of(GREEDY, RANDOM)) {
            run.policy(policy);
            for (var seed = 1; seed <= 3; seed++) {
                addRotations(run, String.valueOf(seed), 3);
            }
        }

        var evaluation = evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING);

        var g07 = evaluation.lines().stream()
                .filter(line -> line.lineId().equals("G-07"))
                .toList();
        assertThat(g07).hasSize(5).allSatisfy(line -> {
            assertThat(line.tableId()).isEqualTo(MIXED);
            assertThat(line.policyId()).isEqualTo(RANDOM);
            assertThat(line.playerCount()).isEqualTo(3);
            assertThat(line.status()).isEqualTo(MetricStatus.NO_DATA);
            assertThat(line.value()).isNull();
            assertThat(line.deviation()).isZero();
            assertThat(line.verdict()).isEqualTo(LineVerdict.INCONCLUSIVE);
        });
        assertThat(g07).extracting(line -> line.dimensions().faction()).containsExactlyElementsOf(ALL);
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.UNCONFIRMED);
    }

    @Test
    void aGameMetricOnTheMixedTableCountsEveryGame() {
        var run = new SyntheticRun("base", GREEDY).table(MIXED, MIXED_SEATS);
        var first = true;
        for (var set : subsets(3)) {
            for (var shift = 0; shift < 3; shift++) {
                if (first) {
                    run.game("1", rotation(set, shift), EndReason.DECK_EXHAUSTED, Set.of(), 5);
                    first = false;
                } else {
                    run.win("1", rotation(set, shift), 1);
                }
            }
        }

        var g03 = only(evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING), "G-03", null, 3);

        assertThat(g03.tableId()).isEqualTo(MIXED);
        assertThat(g03.policyId()).isEqualTo(RANDOM);
        assertThat(g03.value()).isCloseTo(1.0 / 30, within(1e-4));
    }

    @Test
    void aTableWhoseSeatsDifferFromTheCriterionIsNotMeasured() {
        var run = new SyntheticRun("base", GREEDY).table(MIXED, Collections.nCopies(5, GREEDY));
        for (var seed = 1; seed <= 3; seed++) {
            addRotations(run, String.valueOf(seed), 3);
        }

        var evaluation = evaluate(TestCriteria.v2(), run, EvaluationMode.SCREENING);

        assertThat(evaluation.lines())
                .filteredOn(line -> line.tableId().equals(MIXED))
                .isNotEmpty()
                .allSatisfy(line -> assertThat(line.status()).isEqualTo(MetricStatus.NO_DATA));
    }

    @Test
    void aConfirmationWithoutTheMixedTableIsNotAFullMatrix() {
        var uniformOnly = matrix(2, false);
        var everyTable = matrix(2, true);
        var criterion = TestCriteria.v2WithMinimumSeeds(2);

        var without = evaluate(criterion, uniformOnly, EvaluationMode.CONFIRMATION);
        var with = evaluate(criterion, everyTable, EvaluationMode.CONFIRMATION);

        assertThat(without.confirmationSample().seeds()).isEqualTo(2);
        assertThat(without.confirmationSample().fullMatrix()).isFalse();
        assertThat(without.verdict()).isNotEqualTo(EvaluationVerdict.CONFIRMED);
        assertThat(with.confirmationSample().fullMatrix()).isTrue();
    }

    @Test
    void theVersionTwoSampleNeedsOneHundredFourSeeds() {
        var evaluation = evaluate(TestCriteria.v2(), matrix(2, true), EvaluationMode.CONFIRMATION);

        assertThat(evaluation.confirmationSample().requiredSeeds()).isEqualTo(104);
        assertThat(evaluation.confirmationSample().satisfied()).isFalse();
    }

    private static RunEvaluation evaluate(BalanceCriterion criterion, SyntheticRun run, EvaluationMode mode) {
        return RunEvaluator.evaluate(RUN, "base", criterion, mode, AnalysisVersion.CURRENT, run.cases(), run.facts());
    }

    private static EvaluatedLine only(RunEvaluation evaluation, String lineId, Faction faction, int playerCount) {
        var found = evaluation.lines().stream()
                .filter(line -> line.lineId().equals(lineId)
                        && line.dimensions().faction() == faction
                        && line.playerCount() == playerCount
                        && line.status() != MetricStatus.NO_DATA)
                .toList();
        assertThat(found).hasSize(1);
        return found.getFirst();
    }

    /** Faction-greedy games at one player count, every rotation of the full set, won by seat 0. */
    private static SyntheticRun greedyGames(int seeds, int playerCount) {
        var run = new SyntheticRun("base", GREEDY);
        for (var seed = 1; seed <= seeds; seed++) {
            addRotations(run, String.valueOf(seed), playerCount);
        }
        return run;
    }

    /** Every faction set at 3, 4 and 5 players with every rotation on both uniform tables, and the mixed one. */
    private static SyntheticRun matrix(int seeds, boolean mixed) {
        var run = new SyntheticRun("base", GREEDY);
        for (var policy : List.of(GREEDY, RANDOM)) {
            run.policy(policy);
            addMatrix(run, seeds);
        }
        if (mixed) {
            run.table(MIXED, MIXED_SEATS);
            addMatrix(run, seeds);
        }
        return run;
    }

    private static void addMatrix(SyntheticRun run, int seeds) {
        for (var seed = 1; seed <= seeds; seed++) {
            for (var playerCount = 3; playerCount <= 5; playerCount++) {
                addRotations(run, String.valueOf(seed), playerCount);
            }
        }
    }

    private static void addRotations(SyntheticRun run, String seed, int playerCount) {
        for (var set : subsets(playerCount)) {
            for (var shift = 0; shift < playerCount; shift++) {
                run.win(seed, rotation(set, shift), 0);
            }
        }
    }

    private static List<Faction> rotation(List<Faction> set, int shift) {
        var seats = new ArrayList<>(set.subList(shift, set.size()));
        seats.addAll(set.subList(0, shift));
        return seats;
    }

    private static List<List<Faction>> subsets(int size) {
        var sets = new ArrayList<List<Faction>>();
        for (var mask = 0; mask < 1 << ALL.size(); mask++) {
            if (Integer.bitCount(mask) == size) {
                var set = new ArrayList<Faction>();
                for (var index = 0; index < ALL.size(); index++) {
                    if ((mask & (1 << index)) != 0) {
                        set.add(ALL.get(index));
                    }
                }
                sets.add(set);
            }
        }
        return sets;
    }
}
