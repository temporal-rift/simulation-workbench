package io.github.temporalrift.workbench.analysis.domain.evaluation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.domain.AnalysisVersion;
import io.github.temporalrift.workbench.analysis.domain.SyntheticRun;
import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.report.Interval;
import io.github.temporalrift.workbench.analysis.domain.report.MetricStatus;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

class RunEvaluatorTest {

    private static final UUID RUN = new UUID(0, 9);
    private static final String GREEDY = "faction-greedy-v1";
    private static final String RANDOM = "random-v1";
    private static final List<Faction> EPW = SyntheticRun.EPW;

    private final SyntheticRun run = new SyntheticRun("base", GREEDY);

    @Test
    void aFactionThatWinsEveryGameFailsItsScoredLineAndContributesItsWeightedSquaredDeviation() {
        for (var seed = 1; seed <= 8; seed++) {
            run.win(String.valueOf(seed), EPW, 0);
        }

        var evaluation = evaluate(EvaluationMode.SCREENING);

        // Erasers win 8 of 8 seat-games of their own and R is 1/3, so the ratio is 3.
        var erasers = line(evaluation, "L-01", Faction.ERASERS, 3);
        assertThat(erasers.value()).isEqualTo(3.0);
        assertThat(erasers.interval()).isEqualTo(new Interval(3.0, 3.0));
        assertThat(erasers.deviation()).isCloseTo((3.0 - 1.25) / 0.45, within(1e-12));
        assertThat(erasers.verdict()).isEqualTo(LineVerdict.FAILS);
        assertThat(erasers.weight()).isEqualTo(4.0 / 15);
        var prophets = line(evaluation, "L-01", Faction.PROPHETS, 3);
        assertThat(prophets.value()).isZero();
        assertThat(prophets.deviation()).isCloseTo(0.8 / 0.45, within(1e-12));
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.REJECTED);
        assertThat(evaluation.score()).isGreaterThan(erasers.weight() * erasers.deviation() * erasers.deviation());
    }

    @Test
    void noResolutionFailedEndingMeetsItsGateWithZeroValueAndDeviation() {
        for (var seed = 1; seed <= 6; seed++) {
            run.win(String.valueOf(seed), EPW, seed % 3);
        }

        var evaluation = evaluate(EvaluationMode.SCREENING);

        var lines = evaluation.lines().stream()
                .filter(line -> line.lineId().equals("G-02"))
                .toList();
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().value()).isZero();
        assertThat(lines.getFirst().deviation()).isZero();
        assertThat(lines.getFirst().verdict()).isEqualTo(LineVerdict.MEETS);
    }

    @Test
    void failureShareOfCountingCodesRejectsWhenTheWholeIntervalLiesAboveItsLimit() {
        // 1,000 finished cases, 40 failed (30 RUNNER_TIMEOUT, 10 POLICY_EXHAUSTED) in 40 of 100 seeds.
        for (var seed = 1; seed <= 100; seed++) {
            for (var game = 0; game < 10; game++) {
                if (seed <= 40 && game == 0) {
                    run.failed(
                            String.valueOf(seed),
                            EPW,
                            seed <= 30 ? FailureCode.RUNNER_TIMEOUT : FailureCode.POLICY_EXHAUSTED);
                } else {
                    run.win(String.valueOf(seed), EPW, game % 3);
                }
            }
        }

        var evaluation = evaluate(EvaluationMode.SCREENING);

        var g01 = line(evaluation, "G-01", null, 3);
        assertThat(g01.value()).isEqualTo(0.04);
        assertThat(g01.interval().lower()).isGreaterThan(0.02);
        assertThat(g01.verdict()).isEqualTo(LineVerdict.FAILS);
        assertThat(g01.dimensions().failureCodes())
                .containsExactly(FailureCode.RUNNER_TIMEOUT, FailureCode.POLICY_EXHAUSTED);
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.REJECTED);
    }

    @Test
    void aCohortWithOneSeedBlockIsInsufficientAndInconclusive() {
        run.win("1", EPW, 0).win("1", EPW, 1).win("1", EPW, 2);

        var evaluation = evaluate(EvaluationMode.SCREENING);

        // L-07 has no findings to divide by, which is no data rather than a thin sample.
        assertThat(evaluation.lines())
                .filteredOn(line -> !line.lineId().equals("L-07"))
                .isNotEmpty()
                .allSatisfy(line -> {
                    assertThat(line.status()).isEqualTo(MetricStatus.INSUFFICIENT_SAMPLE);
                    assertThat(line.interval()).isNull();
                    assertThat(line.verdict()).isEqualTo(LineVerdict.INCONCLUSIVE);
                });
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.UNCONFIRMED);
    }

    @Test
    void aCohortWithoutAnyWinnerHasNoRatioData() {
        for (var seed = 1; seed <= 5; seed++) {
            run.game(String.valueOf(seed), EPW, EndReason.RESOLUTION_FAILED, Set.of(), 4);
        }

        var evaluation = evaluate(EvaluationMode.SCREENING);

        var ratios = evaluation.lines().stream()
                .filter(line -> line.lineId().equals("L-01") || line.lineId().equals("G-05"))
                .toList();
        assertThat(ratios).hasSize(6).allSatisfy(line -> {
            assertThat(line.status()).isEqualTo(MetricStatus.NO_DATA);
            assertThat(line.value()).isNull();
            assertThat(line.interval()).isNull();
            assertThat(line.deviation()).isZero();
            assertThat(line.verdict()).isEqualTo(LineVerdict.INCONCLUSIVE);
        });
        assertThat(line(evaluation, "G-02", null, 3).verdict()).isEqualTo(LineVerdict.FAILS);
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.REJECTED);
    }

    @Test
    void configurationDriftInvalidatesAndAPendingCaseAddsItsOwnReason() {
        for (var seed = 1; seed <= 4; seed++) {
            run.win(String.valueOf(seed), EPW, seed % 3);
        }
        run.failed("5", EPW, FailureCode.CONFIGURATION_DRIFT);

        var drift = evaluate(EvaluationMode.SCREENING);

        assertThat(drift.verdict()).isEqualTo(EvaluationVerdict.INVALID);
        assertThat(drift.score()).isNull();
        assertThat(drift.validity().valid()).isFalse();
        assertThat(drift.validity().reasons())
                .extracting(Validity.Reason::code)
                .containsExactly(ValidityCode.CONFIGURATION_DRIFT);

        run.unfinished("6", EPW, CaseState.PENDING);

        var both = evaluate(EvaluationMode.SCREENING);

        assertThat(both.verdict()).isEqualTo(EvaluationVerdict.INVALID);
        assertThat(both.validity().reasons())
                .extracting(Validity.Reason::code)
                .containsExactly(ValidityCode.UNFINISHED_CASES, ValidityCode.CONFIGURATION_DRIFT);
    }

    @Test
    void anExecutionFailedShareAboveTheLimitAndAnAbandonedGameInvalidate() {
        for (var seed = 1; seed <= 10; seed++) {
            run.win(String.valueOf(seed), EPW, seed % 3);
        }
        run.failed("11", EPW, FailureCode.EXECUTION_FAILED)
                .failed("12", EPW, FailureCode.EXECUTION_FAILED)
                .game("13", EPW, EndReason.ALL_PLAYERS_ABANDONED, Set.of(), 2);

        var evaluation = evaluate(EvaluationMode.SCREENING);

        assertThat(evaluation.validity().reasons())
                .extracting(Validity.Reason::code)
                .containsExactly(ValidityCode.EXECUTION_FAILED_SHARE, ValidityCode.ALL_PLAYERS_ABANDONED);
    }

    @Test
    void screeningNeverConfirmsAndCarriesNoSample() {
        var evaluation = evaluate(EvaluationMode.SCREENING, balancedMatrix(4));

        assertThat(evaluation.confirmationSample()).isNull();
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.UNCONFIRMED);
    }

    @Test
    void aFullBalancedMatrixOnEnoughSeedsConfirmsInConfirmationMode() {
        var evaluation = evaluate(EvaluationMode.CONFIRMATION, balancedMatrix(4), TestCriteria.v1WithMinimumSeeds(4));

        assertThat(evaluation.confirmationSample().satisfied()).isTrue();
        assertThat(evaluation.confirmationSample().fullMatrix()).isTrue();
        assertThat(evaluation.validity().valid()).isTrue();
        assertThat(evaluation.lines())
                .filteredOn(line -> line.role() == CriterionRole.GATE && line.pooled())
                .allSatisfy(line -> assertThat(line.verdict()).isEqualTo(LineVerdict.MEETS));
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.CONFIRMED);
        assertThat(evaluation.score()).isZero();
    }

    @Test
    void aShortSampleDoesNotConfirm() {
        var evaluation = evaluate(EvaluationMode.CONFIRMATION, balancedMatrix(4));

        assertThat(evaluation.confirmationSample().requiredSeeds()).isEqualTo(100);
        assertThat(evaluation.confirmationSample().seeds()).isEqualTo(4);
        assertThat(evaluation.confirmationSample().satisfied()).isFalse();
        assertThat(evaluation.verdict()).isEqualTo(EvaluationVerdict.UNCONFIRMED);
    }

    @Test
    void anIncompleteMatrixDoesNotSatisfyTheSampleEvenWithEnoughSeeds() {
        var cases = balancedMatrix(4);
        cases.cases().removeLast();

        var evaluation = evaluate(EvaluationMode.CONFIRMATION, cases, TestCriteria.v1WithMinimumSeeds(4));

        assertThat(evaluation.confirmationSample().fullMatrix()).isFalse();
        assertThat(evaluation.verdict()).isNotEqualTo(EvaluationVerdict.CONFIRMED);
    }

    @Test
    void theSameCasesAlwaysGiveTheSameEvaluationOrderedByLinePolicyPlayersSetAndFaction() {
        for (var seed = 1; seed <= 8; seed++) {
            run.win(String.valueOf(seed), EPW, seed % 3);
        }
        run.policy(RANDOM);
        for (var seed = 1; seed <= 8; seed++) {
            run.win(String.valueOf(seed), EPW, seed % 2);
        }

        var first = evaluate(EvaluationMode.SCREENING);
        var again = evaluate(EvaluationMode.SCREENING);

        assertThat(again).isEqualTo(first);
        var order = first.lines().stream()
                .map(line -> line.lineId() + "/" + line.policyId() + "/" + line.playerCount())
                .toList();
        assertThat(order).isSorted();
        var g05 = first.lines().stream()
                .filter(line -> line.lineId().equals("G-05"))
                .map(line -> line.dimensions().faction())
                .toList();
        assertThat(g05).isSortedAccordingTo(java.util.Comparator.comparing(Faction::name));
    }

    private RunEvaluation evaluate(EvaluationMode mode) {
        return evaluate(mode, run);
    }

    private static RunEvaluation evaluate(EvaluationMode mode, SyntheticRun cases) {
        return evaluate(mode, cases, TestCriteria.v1());
    }

    private static RunEvaluation evaluate(EvaluationMode mode, SyntheticRun cases, BalanceCriterion criterion) {
        return RunEvaluator.evaluate(
                RUN, "base", criterion, mode, AnalysisVersion.CURRENT, cases.cases(), cases.facts());
    }

    private static EvaluatedLine line(RunEvaluation evaluation, String lineId, Faction faction, int playerCount) {
        return evaluation.lines().stream()
                .filter(line -> line.lineId().equals(lineId)
                        && line.dimensions().faction() == faction
                        && line.playerCount() == playerCount
                        && line.policyId().equals(GREEDY))
                .findFirst()
                .orElseThrow();
    }

    /**
     * Every faction set at 3, 4 and 5 players with every cyclic rotation, for both policies and the given seeds.
     * The seat 0 player always wins, so every faction wins equally often.
     */
    private static SyntheticRun balancedMatrix(int seeds) {
        var cases = new SyntheticRun("base", GREEDY).withParadoxes();
        for (var policy : List.of(GREEDY, RANDOM)) {
            cases.policy(policy);
            for (var seed = 1; seed <= seeds; seed++) {
                for (var playerCount = 3; playerCount <= 5; playerCount++) {
                    addRotations(cases, String.valueOf(seed), playerCount);
                }
            }
        }
        return cases;
    }

    private static void addRotations(SyntheticRun cases, String seed, int playerCount) {
        for (var set : subsets(playerCount)) {
            for (var shift = 0; shift < playerCount; shift++) {
                var seats = new ArrayList<>(set.subList(shift, playerCount));
                seats.addAll(set.subList(0, shift));
                cases.win(seed, seats, 0);
            }
        }
    }

    private static List<List<Faction>> subsets(int size) {
        var roster = List.of(Faction.values());
        var sets = new ArrayList<List<Faction>>();
        for (var mask = 0; mask < 1 << roster.size(); mask++) {
            if (Integer.bitCount(mask) == size) {
                var set = new ArrayList<Faction>();
                for (var index = 0; index < roster.size(); index++) {
                    if ((mask & (1 << index)) != 0) {
                        set.add(roster.get(index));
                    }
                }
                set.sort(java.util.Comparator.comparing(Faction::name));
                sets.add(set);
            }
        }
        return sets;
    }
}
