package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.temporalrift.workbench.execution.domain.port.out.AttemptTimings;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.CaseCounts;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LaneNotCleanException;
import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCancelledException;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.execution.support.Provenances;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

class RunBatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID RUN = new UUID(1, 1);
    private static final BundleDigests BASELINE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final LaneFit FIT = Provenances.fitOf(BASELINE);
    private static final LaneFit OTHER_FIT = Provenances.fitOf(new BundleDigests("a".repeat(64), "c".repeat(64)));
    private static final CaseRules RULES = new CaseRules(new TreeMap<>(), "e".repeat(64));

    private final RunRepository runs = mock(RunRepository.class);
    private final CaseLedger cases = mock(CaseLedger.class);
    private final ExperimentSource experiments = mock(ExperimentSource.class);
    private final LaneProvider lanes = mock(LaneProvider.class);
    private final CaseDriver driver = mock(CaseDriver.class);
    private final List<Timed> ended = new ArrayList<>();
    private final AttemptTimings timings =
            (lane, players, state, failure, elapsed) -> ended.add(new Timed(lane, players, state, failure));
    private final RunBatchService service = new RunBatchService(
            runs,
            cases,
            experiments,
            new RunBatchService.Playing(lanes, driver, timings),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ExecutionSettings(Duration.ofSeconds(30), Duration.ofSeconds(5), 2, NOW));

    @Test
    void aRunWhoseFrozenExperimentIsGoneFailsAndItsPendingCasesAreCancelled() {
        var running = Run.queued(RUN, new UUID(2, 2), NOW).begin(NOW);
        var logicalCase = logicalCase(FIT);
        var attempt = attempt(logicalCase);
        when(lanes.freeLanes()).thenReturn(Map.of("lane-a", FIT));
        when(cases.claimNext(any(), any(), any(), any()))
                .thenReturn(Optional.of(new CaseLedger.Claim(logicalCase, attempt, Optional.empty(), 1)));
        when(runs.find(RUN)).thenReturn(Optional.of(running));
        when(experiments.plan(running.experimentId())).thenReturn(Optional.empty());
        when(runs.update(eq(RunState.RUNNING), any())).thenReturn(true);

        assertThat(service.runNextCase("worker")).isTrue();

        var failed = ArgumentCaptor.forClass(Run.class);
        verify(runs).update(eq(RunState.RUNNING), failed.capture());
        assertThat(failed.getValue().state()).isEqualTo(RunState.FAILED);
        assertThat(failed.getValue().failure().code()).isEqualTo(FailureCode.CONTRACT_MISMATCH);
        verify(cases).release(attempt.attemptId(), "worker");
        verify(runs).cancelPendingCases(RUN);
        verify(lanes, never()).acquire(any(), any());
    }

    @Test
    void nothingIsRunWhenTheLedgerHasNothingToClaim() {
        when(lanes.freeLanes()).thenReturn(Map.of());
        when(cases.claimNext(any(), any(), any(), any())).thenReturn(Optional.empty());

        assertThat(service.runNextCase("worker")).isFalse();

        verify(cases).claimNext(eq("worker"), any(), any(), eq(Set.of()));
    }

    @Test
    void onlyCasesTheFreeLanesFitAreClaimed() {
        when(lanes.freeLanes()).thenReturn(Map.of("lane-a", FIT, "lane-b", FIT, "lane-c", OTHER_FIT));

        service.runNextCase("worker");

        verify(cases).claimNext("worker", NOW, NOW.plusSeconds(30), Set.of(FIT, OTHER_FIT));
    }

    @Test
    void aClaimedCaseAcquiresALaneThatFitsItPreferringItsPreviousLane() {
        var logicalCase = logicalCase(OTHER_FIT);
        var attempt = attempt(logicalCase);
        var previous = new Attempt(
                new UUID(6, 6), logicalCase.caseId(), 0, AttemptState.INTERRUPTED, null, "lane-c", NOW, NOW, null);
        when(lanes.freeLanes()).thenReturn(Map.of("lane-c", OTHER_FIT));
        when(cases.claimNext(any(), any(), any(), any()))
                .thenReturn(Optional.of(new CaseLedger.Claim(logicalCase, attempt, Optional.of(previous), 1)));
        when(runs.find(RUN))
                .thenReturn(Optional.of(Run.queued(RUN, new UUID(2, 2), NOW).begin(NOW)));
        when(experiments.plan(any()))
                .thenReturn(Optional.of(new ExperimentSource.Plan("d", "{}", 1, 60, 1, List.of())));
        when(experiments.rules(any(), any())).thenReturn(Optional.of(RULES));
        when(lanes.acquire(OTHER_FIT, "lane-c")).thenReturn(Optional.empty());

        assertThat(service.runNextCase("worker")).isFalse();

        verify(lanes).acquire(OTHER_FIT, "lane-c");
        verify(cases).release(attempt.attemptId(), "worker");
    }

    @Test
    void aRestartInterruptsTheAttemptsThenPreparesTheLanes() {
        service.recoverAfterRestart();

        var order = inOrder(cases, lanes);
        order.verify(cases).interruptAllRunning(NOW);
        order.verify(lanes).prepare();
    }

    @Test
    void maintenanceSettlesEveryRunningRunFromOneCountsRead() {
        var finished = new UUID(3, 3);
        var busy = new UUID(4, 4);
        var drained = new UUID(5, 5);
        var experiment = new UUID(2, 2);
        when(runs.findByState(RunState.RUNNING))
                .thenReturn(List.of(
                        Run.queued(finished, experiment, NOW).begin(NOW),
                        Run.queued(busy, experiment, NOW).begin(NOW),
                        Run.queued(drained, experiment, NOW).begin(NOW)));
        when(runs.countsOf(List.of(finished, busy, drained)))
                .thenReturn(Map.of(
                        finished, new CaseCounts(2, 0, 0, 2, 0, 0),
                        busy, new CaseCounts(2, 1, 1, 0, 0, 0),
                        drained, new CaseCounts(2, 0, 0, 1, 1, 0)));
        when(runs.update(eq(RunState.RUNNING), any())).thenReturn(true);

        service.maintain();

        verify(runs).countsOf(any());
        verify(runs, never()).counts(any());
        var completed = ArgumentCaptor.forClass(Run.class);
        verify(runs, times(2)).update(eq(RunState.RUNNING), completed.capture());
        assertThat(completed.getAllValues()).extracting(Run::runId).containsExactly(finished, drained);
    }

    @Test
    void maintenanceCancelsPendingCasesThenFinishesCancellationsFromOneCountsRead() {
        var stopped = new UUID(3, 3);
        var stopping = new UUID(4, 4);
        var experiment = new UUID(2, 2);
        when(runs.findByState(RunState.CANCELLING))
                .thenReturn(List.of(
                        Run.queued(stopped, experiment, NOW).begin(NOW).requestCancel(),
                        Run.queued(stopping, experiment, NOW).begin(NOW).requestCancel()));
        when(runs.countsOf(List.of(stopped, stopping)))
                .thenReturn(
                        Map.of(stopped, new CaseCounts(2, 0, 0, 1, 0, 1), stopping, new CaseCounts(2, 0, 1, 0, 0, 1)));
        when(runs.update(eq(RunState.CANCELLING), any())).thenReturn(true);

        service.maintain();

        verify(runs).cancelPendingCases(stopped);
        verify(runs).cancelPendingCases(stopping);
        verify(runs).countsOf(any());
        var finished = ArgumentCaptor.forClass(Run.class);
        verify(runs).update(eq(RunState.CANCELLING), finished.capture());
        assertThat(finished.getValue().runId()).isEqualTo(stopped);
    }

    @Test
    void maintenanceReadsNoCountsWhenNoRunIsRunningOrCancelling() {
        service.maintain();

        verify(runs, never()).countsOf(any());
    }

    @Test
    void aSucceededAttemptHandsItsLaneBack() {
        var lane = playing();
        when(cases.succeed(any(), any(), any(), any())).thenReturn(true);

        service.runNextCase("worker");

        verify(lane).close();
    }

    @Test
    void aFailureThatReturnsTheCaseToPendingHandsItsLaneBack() {
        var lane = playing();
        when(driver.play(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AttemptFailedException(FailureCode.EXECUTION_FAILED, "lane fault"));
        when(cases.fail(any(), any(), any(), eq(true), any())).thenReturn(CaseLedger.FailOutcome.RETRY);

        service.runNextCase("worker");

        verify(lane).close();
    }

    @Test
    void aLaneThatStillHeldAnEarlierCaseGivesTheClaimBackUnspentAndHandsTheLaneBack() {
        var lane = playing();
        when(driver.play(any(), any(), any(), any(), any(), any()))
                .thenThrow(new LaneNotCleanException("game-service of lane lane-1 still holds an earlier case"));

        service.runNextCase("worker");

        verify(cases).release(any(), eq("worker"));
        verify(cases, never()).fail(any(), any(), any(), anyBoolean(), any());
        verify(lane).close();
    }

    @Test
    void aCancelledAttemptHandsItsLaneBack() {
        var lane = playing();
        when(driver.play(any(), any(), any(), any(), any(), any())).thenThrow(new RunCancelledException());
        when(cases.cancel(any(), any(), any())).thenReturn(true);

        service.runNextCase("worker");

        verify(lane).close();
    }

    @Test
    void anAttemptThatLostItsLeaseStillHandsItsLaneBack() {
        var lane = playing();
        when(driver.play(any(), any(), any(), any(), any(), any())).thenThrow(new LeaseLostException());

        service.runNextCase("worker");

        verify(lane).close();
    }

    @Test
    void anAttemptWhoseResultWasFencedOutStillHandsItsLaneBack() {
        var lane = playing();
        when(cases.succeed(any(), any(), any(), any())).thenReturn(false);

        service.runNextCase("worker");

        verify(lane).close();
    }

    @Test
    void aRunWhoseVariantRulesAreGoneFailsAndNoLaneIsAcquired() {
        var running = Run.queued(RUN, new UUID(2, 2), NOW).begin(NOW);
        var logicalCase = logicalCase(FIT);
        var attempt = attempt(logicalCase);
        when(lanes.freeLanes()).thenReturn(Map.of("lane-a", FIT));
        when(cases.claimNext(any(), any(), any(), any()))
                .thenReturn(Optional.of(new CaseLedger.Claim(logicalCase, attempt, Optional.empty(), 1)));
        when(runs.find(RUN)).thenReturn(Optional.of(running));
        when(experiments.plan(any())).thenReturn(Optional.of(plan()));
        when(experiments.rules(running.experimentId(), "v")).thenReturn(Optional.empty());
        when(runs.update(eq(RunState.RUNNING), any())).thenReturn(true);

        assertThat(service.runNextCase("worker")).isTrue();

        var failed = ArgumentCaptor.forClass(Run.class);
        verify(runs).update(eq(RunState.RUNNING), failed.capture());
        assertThat(failed.getValue().failure().message()).contains("v");
        verify(cases).release(attempt.attemptId(), "worker");
        verify(lanes, never()).acquire(any(), any());
    }

    @Test
    void anAttemptThatSucceedsIsTimedAsSucceededWithoutAFailure() {
        playing();
        when(cases.succeed(any(), any(), any(), any())).thenReturn(true);

        service.runNextCase("worker");

        assertThat(ended).containsExactly(new Timed("lane-a", 0, AttemptState.SUCCEEDED, null));
    }

    @Test
    void anAttemptThatTimesOutIsTimedAsFailedWithItsFailureCode() {
        playing();
        when(driver.play(any(), any(), any(), any(), any(), any()))
                .thenThrow(new AttemptFailedException(FailureCode.RUNNER_TIMEOUT, "too slow"));
        when(cases.fail(any(), any(), any(), anyBoolean(), any())).thenReturn(CaseLedger.FailOutcome.RETRY);

        service.runNextCase("worker");

        assertThat(ended).containsExactly(new Timed("lane-a", 0, AttemptState.FAILED, FailureCode.RUNNER_TIMEOUT));
    }

    @Test
    void aCancelledAttemptIsTimedAsCancelled() {
        playing();
        when(driver.play(any(), any(), any(), any(), any(), any())).thenThrow(new RunCancelledException());
        when(cases.cancel(any(), any(), any())).thenReturn(true);

        service.runNextCase("worker");

        assertThat(ended).containsExactly(new Timed("lane-a", 0, AttemptState.CANCELLED, null));
    }

    @Test
    void anAttemptThatLostItsLeaseOrWasFencedOutIsTimedAsInterrupted() {
        playing();
        when(driver.play(any(), any(), any(), any(), any(), any()))
                .thenThrow(new LeaseLostException())
                .thenThrow(new AttemptFailedException(FailureCode.POLICY_EXHAUSTED, "exhausted"));
        when(cases.fail(any(), any(), any(), anyBoolean(), any())).thenReturn(CaseLedger.FailOutcome.LOST);

        service.runNextCase("worker");
        service.runNextCase("worker");

        assertThat(ended)
                .containsExactly(
                        new Timed("lane-a", 0, AttemptState.INTERRUPTED, null),
                        new Timed("lane-a", 0, AttemptState.INTERRUPTED, null));
    }

    private record Timed(String lane, int players, AttemptState state, FailureCode failure) {}

    /** A claimed case that acquired a lane and is about to play on it. */
    private CaseLane playing() {
        var logicalCase = logicalCase(FIT);
        var lane = mock(CaseLane.class);
        when(lane.laneId()).thenReturn("lane-a");
        when(lanes.freeLanes()).thenReturn(Map.of("lane-a", FIT));
        when(cases.claimNext(any(), any(), any(), any()))
                .thenReturn(Optional.of(new CaseLedger.Claim(logicalCase, attempt(logicalCase), Optional.empty(), 1)));
        when(runs.find(RUN))
                .thenReturn(Optional.of(Run.queued(RUN, new UUID(2, 2), NOW).begin(NOW)));
        when(experiments.plan(any())).thenReturn(Optional.of(plan()));
        when(experiments.rules(any(), any())).thenReturn(Optional.of(RULES));
        when(lanes.acquire(FIT, null)).thenReturn(Optional.of(lane));
        return lane;
    }

    private static ExperimentSource.Plan plan() {
        return new ExperimentSource.Plan("d", "{}", 1, 60, 1, List.of());
    }

    private static LogicalCase logicalCase(LaneFit fit) {
        return new LogicalCase(
                new UUID(3, 3),
                RUN,
                new UUID(4, 4),
                0,
                "v",
                BASELINE,
                fit,
                "42",
                3,
                List.of(),
                CaseState.RUNNING,
                null);
    }

    private static Attempt attempt(LogicalCase logicalCase) {
        return new Attempt(new UUID(5, 5), logicalCase.caseId(), 1, AttemptState.RUNNING, null, null, NOW, null, null);
    }
}
