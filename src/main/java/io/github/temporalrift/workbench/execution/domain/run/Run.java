package io.github.temporalrift.workbench.execution.domain.run;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A durable batch over a frozen experiment. Transitions return a new value and reject illegal
 * moves; the repository applies them with compare-and-set on the previous state so racing workers
 * and designers cannot both win a transition.
 *
 * <pre>
 * QUEUED ──begin──▶ RUNNING ──complete──▶ COMPLETED
 *    ▲                 │ ╲──fail──▶ FAILED
 *    │             interrupt
 *    │                 ▼
 *    └────resume── INTERRUPTED
 *
 * cancel (from QUEUED, RUNNING, INTERRUPTED) ──▶ CANCELLING ──▶ CANCELLED
 * </pre>
 */
public final class Run {

    private final UUID runId;
    private final UUID experimentId;
    private final RunState state;
    private final Instant createdAt;
    private final Instant startedAt;
    private final Instant finishedAt;
    private final Failure failure;

    private Run(
            UUID runId,
            UUID experimentId,
            RunState state,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Failure failure) {
        this.runId = Objects.requireNonNull(runId, "runId");
        this.experimentId = Objects.requireNonNull(experimentId, "experimentId");
        this.state = Objects.requireNonNull(state, "state");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.startedAt = startedAt;
        this.finishedAt = finishedAt;
        this.failure = failure;
    }

    public static Run queued(UUID runId, UUID experimentId, Instant now) {
        return new Run(runId, experimentId, RunState.QUEUED, now, null, null, null);
    }

    /** Rebuilds a persisted run. */
    public static Run restore(
            UUID runId,
            UUID experimentId,
            RunState state,
            Instant createdAt,
            Instant startedAt,
            Instant finishedAt,
            Failure failure) {
        return new Run(runId, experimentId, state, createdAt, startedAt, finishedAt, failure);
    }

    /** The worker starts executing a queued run. A resumed run keeps its original start time. */
    public Run begin(Instant now) {
        requireState("begin", RunState.QUEUED);
        return with(RunState.RUNNING, startedAt != null ? startedAt : now, null, null);
    }

    /** The runner stopped before the run finished; completed cases stay as they are. */
    public Run interrupt() {
        requireState("interrupt", RunState.RUNNING);
        return with(RunState.INTERRUPTED, startedAt, null, null);
    }

    /** Designer resume; legal only from {@link RunState#INTERRUPTED}. */
    public Run resume() {
        requireState("resume", RunState.INTERRUPTED);
        return with(RunState.QUEUED, startedAt, null, null);
    }

    /** Stops scheduling new cases. Active cases are signalled; {@link #finishCancel} follows once none run. */
    public Run requestCancel() {
        requireState("cancel", RunState.QUEUED, RunState.RUNNING, RunState.INTERRUPTED);
        return with(RunState.CANCELLING, startedAt, null, null);
    }

    public Run finishCancel(Instant now) {
        requireState("finish cancellation of", RunState.CANCELLING);
        return with(RunState.CANCELLED, startedAt, now, null);
    }

    public Run complete(Instant now) {
        requireState("complete", RunState.RUNNING);
        return with(RunState.COMPLETED, startedAt, now, null);
    }

    public Run fail(Instant now, Failure reason) {
        requireState("fail", RunState.QUEUED, RunState.RUNNING);
        return with(RunState.FAILED, startedAt != null ? startedAt : now, now, Objects.requireNonNull(reason));
    }

    private Run with(RunState next, Instant started, Instant finished, Failure reason) {
        return new Run(runId, experimentId, next, createdAt, started, finished, reason);
    }

    private void requireState(String operation, RunState... allowed) {
        for (RunState candidate : allowed) {
            if (state == candidate) {
                return;
            }
        }
        throw new InvalidRunStateException(operation, state);
    }

    public UUID runId() {
        return runId;
    }

    public UUID experimentId() {
        return experimentId;
    }

    public RunState state() {
        return state;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant finishedAt() {
        return finishedAt;
    }

    public Failure failure() {
        return failure;
    }
}
