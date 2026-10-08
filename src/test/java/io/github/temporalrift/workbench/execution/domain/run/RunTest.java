package io.github.temporalrift.workbench.execution.domain.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RunTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final Instant T2 = T0.plusSeconds(120);

    private static Run queued() {
        return Run.queued(UUID.randomUUID(), UUID.randomUUID(), T0);
    }

    @Test
    void aRunIsQueuedWithoutStartOrFinishTimes() {
        var run = queued();

        assertThat(run.state()).isEqualTo(RunState.QUEUED);
        assertThat(run.createdAt()).isEqualTo(T0);
        assertThat(run.startedAt()).isNull();
        assertThat(run.finishedAt()).isNull();
        assertThat(run.failure()).isNull();
    }

    @Test
    void theHappyPathQueuedRunningCompleted() {
        var running = queued().begin(T1);
        var completed = running.complete(T2);

        assertThat(running.state()).isEqualTo(RunState.RUNNING);
        assertThat(running.startedAt()).isEqualTo(T1);
        assertThat(completed.state()).isEqualTo(RunState.COMPLETED);
        assertThat(completed.finishedAt()).isEqualTo(T2);
        assertThat(completed.startedAt()).isEqualTo(T1);
    }

    @Test
    void anInterruptedRunResumesThroughQueuedAndKeepsItsOriginalStartTime() {
        var interrupted = queued().begin(T1).interrupt();

        var resumed = interrupted.resume();
        var running = resumed.begin(T2);

        assertThat(interrupted.state()).isEqualTo(RunState.INTERRUPTED);
        assertThat(resumed.state()).isEqualTo(RunState.QUEUED);
        assertThat(running.state()).isEqualTo(RunState.RUNNING);
        assertThat(running.startedAt()).isEqualTo(T1);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunState.class,
            names = {"QUEUED", "RUNNING", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED"})
    void resumeIsLegalOnlyFromInterrupted(RunState state) {
        var run = inState(state);

        assertThatThrownBy(run::resume)
                .isInstanceOfSatisfying(
                        InvalidRunStateException.class,
                        e -> assertThat(e.state()).isEqualTo(state));
    }

    @ParameterizedTest
    @EnumSource(
            value = RunState.class,
            names = {"QUEUED", "RUNNING", "INTERRUPTED"})
    void cancellationStartsFromAnyUnfinishedState(RunState state) {
        assertThat(inState(state).requestCancel().state()).isEqualTo(RunState.CANCELLING);
    }

    @ParameterizedTest
    @EnumSource(
            value = RunState.class,
            names = {"CANCELLING", "CANCELLED", "COMPLETED", "FAILED"})
    void aFinishedOrCancellingRunCannotBeCancelledAgain(RunState state) {
        assertThatThrownBy(inState(state)::requestCancel).isInstanceOf(InvalidRunStateException.class);
    }

    @Test
    void cancellationFinishesOnlyFromCancelling() {
        var cancelled = queued().requestCancel().finishCancel(T1);

        assertThat(cancelled.state()).isEqualTo(RunState.CANCELLED);
        assertThat(cancelled.finishedAt()).isEqualTo(T1);
        assertThatThrownBy(() -> queued().finishCancel(T1)).isInstanceOf(InvalidRunStateException.class);
    }

    @Test
    void completionIsLegalOnlyWhileRunning() {
        assertThatThrownBy(() -> queued().complete(T1)).isInstanceOf(InvalidRunStateException.class);
        assertThatThrownBy(() -> queued().begin(T1).interrupt().complete(T2))
                .isInstanceOf(InvalidRunStateException.class);
    }

    @Test
    void aFailedRunKeepsItsReason() {
        var failure = new Failure(FailureCode.CONTRACT_MISMATCH, "unsupported contract");

        var failed = queued().begin(T1).fail(T2, failure);

        assertThat(failed.state()).isEqualTo(RunState.FAILED);
        assertThat(failed.failure()).isEqualTo(failure);
        assertThat(failed.finishedAt()).isEqualTo(T2);
        assertThat(failed.state().isTerminal()).isTrue();
    }

    @Test
    void onlyCompletedCancelledAndFailedAreTerminal() {
        for (var state : RunState.values()) {
            var terminal = state == RunState.COMPLETED || state == RunState.CANCELLED || state == RunState.FAILED;
            assertThat(state.isTerminal()).as(state.name()).isEqualTo(terminal);
        }
    }

    private static Run inState(RunState state) {
        var run = queued();
        return switch (state) {
            case QUEUED -> run;
            case RUNNING -> run.begin(T1);
            case INTERRUPTED -> run.begin(T1).interrupt();
            case CANCELLING -> run.requestCancel();
            case CANCELLED -> run.requestCancel().finishCancel(T2);
            case COMPLETED -> run.begin(T1).complete(T2);
            case FAILED -> run.begin(T1).fail(T2, new Failure(FailureCode.EXECUTION_FAILED, "boom"));
        };
    }
}
