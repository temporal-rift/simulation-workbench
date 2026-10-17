package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.AttemptTimings;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCancelledException;
import io.github.temporalrift.workbench.execution.domain.run.RunState;

/**
 * Executes the pending cases of running runs, each only on a lane serving its variant's bundle. A claim opens a new
 * attempt that owns the case through a renewable lease; every outcome is fenced by that lease, so an interrupted or
 * stalled worker can never overwrite a case another attempt recovered, and a logical case is recorded as succeeded at
 * most once. An interrupted attempt leaves its lane holding the game for the case's recovering attempt; every other
 * outcome hands the lane back to be made clean.
 */
public class RunBatchService implements RunBatchUseCase {

    /** What plays a claimed case: where it runs, how it is driven, and how its attempt is timed. */
    public record Playing(LaneProvider lanes, CaseDriver driver, AttemptTimings timings) {}

    private final RunRepository runs;
    private final CaseLedger cases;
    private final ExperimentSource experiments;
    private final Playing playing;
    private final Clock clock;
    private final ExecutionSettings settings;

    public RunBatchService(
            RunRepository runs,
            CaseLedger cases,
            ExperimentSource experiments,
            Playing playing,
            Clock clock,
            ExecutionSettings settings) {
        this.runs = runs;
        this.cases = cases;
        this.experiments = experiments;
        this.playing = playing;
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public void recoverAfterRestart() {
        cases.interruptAllRunning(clock.instant());
        playing.lanes().prepare(cases.interruptedLanes());
    }

    @Override
    public void maintain() {
        cases.interruptExpired(clock.instant());
        runs.findByState(RunState.QUEUED).forEach(this::begin);
        runs.findByState(RunState.RUNNING).forEach(this::settleRunning);
        runs.findByState(RunState.CANCELLING).forEach(this::settleCancelling);
        cases.finished(playing.lanes().heldCases()).forEach(playing.lanes()::releaseHeld);
    }

    @Override
    public boolean runNextCase(String owner) {
        var freeBundles = Set.copyOf(playing.lanes().freeLanes().values());
        var heldCases = playing.lanes().heldCases();
        if (freeBundles.isEmpty() && heldCases.isEmpty()) {
            return false;
        }
        var now = clock.instant();
        var claim = cases.claimNext(owner, now, now.plus(settings.lease()), freeBundles, heldCases);
        if (claim.isEmpty()) {
            return false;
        }
        var attempt = claim.get().attempt();
        var previous = claim.get().previous().orElse(null);
        var run = runs.find(claim.get().logicalCase().runId()).orElseThrow();
        var plan = experiments.plan(run.experimentId());
        if (plan.isEmpty()) {
            failRun(run, attempt, owner, now);
            return true;
        }
        var logicalCase = claim.get().logicalCase();
        var lane = playing.lanes()
                .acquire(logicalCase.caseId(), logicalCase.bundle(), previous == null ? null : previous.laneId());
        if (lane.isEmpty()) {
            cases.release(attempt.attemptId(), owner, now);
            return false;
        }
        execute(claim.get(), previous, plan.get(), lane.get(), owner);
        return true;
    }

    @Override
    public void shutdown(String owner) {
        cases.interruptOwnedBy(owner, clock.instant());
    }

    private void execute(
            CaseLedger.Claim claim, Attempt previous, ExperimentSource.Plan plan, CaseLane lane, String owner) {
        try (lane) {
            if (!playToOutcome(claim, previous, plan, lane, owner)) {
                lane.retainForRecovery();
            }
        }
    }

    /** Plays the attempt and records its outcome; false when the attempt was interrupted instead. */
    private boolean playToOutcome(
            CaseLedger.Claim claim, Attempt previous, ExperimentSource.Plan plan, CaseLane lane, String owner) {
        var attempt = claim.attempt();
        var started = clock.instant();
        try {
            var result = playing.driver()
                    .play(claim.logicalCase(), attempt, previous, plan, lane, guard(attempt.attemptId(), claim, owner));
            return ended(
                    claim,
                    lane,
                    started,
                    cases.succeed(attempt.attemptId(), owner, result, clock.instant()),
                    AttemptState.SUCCEEDED,
                    null);
        } catch (AttemptFailedException e) {
            return ended(
                    claim,
                    lane,
                    started,
                    fail(attempt, owner, e.failure()),
                    AttemptState.FAILED,
                    e.failure().code());
        } catch (RunCancelledException _) {
            return ended(
                    claim,
                    lane,
                    started,
                    cases.cancel(attempt.attemptId(), owner, clock.instant()),
                    AttemptState.CANCELLED,
                    null);
        } catch (LeaseLostException _) {
            // Another attempt owns the case now; nothing this worker holds is left to settle.
            return ended(claim, lane, started, false, AttemptState.INTERRUPTED, null);
        } catch (RuntimeException e) {
            var failure = new Failure(FailureCode.EXECUTION_FAILED, describe(e));
            return ended(claim, lane, started, fail(attempt, owner, failure), AttemptState.FAILED, failure.code());
        }
    }

    /** Reports the attempt's wall time; an attempt that no longer owns its case ended interrupted, whatever it saw. */
    private boolean ended(
            CaseLedger.Claim claim,
            CaseLane lane,
            Instant started,
            boolean owned,
            AttemptState state,
            FailureCode failure) {
        playing.timings()
                .ended(
                        lane.laneId(),
                        claim.logicalCase().seats().size(),
                        owned ? state : AttemptState.INTERRUPTED,
                        owned ? failure : null,
                        Duration.between(started, clock.instant()));
        return owned;
    }

    /** The frozen experiment is gone, so no case of the run can ever execute: the run itself fails. */
    private void failRun(Run run, Attempt attempt, String owner, Instant now) {
        cases.release(attempt.attemptId(), owner, now);
        var reason = new Failure(FailureCode.CONTRACT_MISMATCH, "The frozen experiment is unavailable");
        if (runs.update(RunState.RUNNING, run.fail(now, reason))) {
            runs.cancelPendingCases(run.runId());
        }
    }

    private boolean fail(Attempt attempt, String owner, Failure failure) {
        var retry = failure.code().isRetryable() && attempt.ordinal() < settings.maxAttemptsPerCase();
        return cases.fail(attempt.attemptId(), owner, failure, retry, clock.instant()) != CaseLedger.FailOutcome.LOST;
    }

    private AttemptGuard guard(UUID attemptId, CaseLedger.Claim claim, String owner) {
        return new AttemptGuard() {
            private Instant lastBeat = clock.instant();

            @Override
            public void checkpoint() {
                var now = clock.instant();
                if (now.isBefore(lastBeat.plus(settings.heartbeat()))) {
                    return;
                }
                lastBeat = now;
                if (!cases.extendLease(attemptId, owner, now.plus(settings.lease()))) {
                    throw new LeaseLostException();
                }
                var state =
                        runs.find(claim.logicalCase().runId()).map(Run::state).orElse(RunState.CANCELLED);
                if (state == RunState.CANCELLING || state == RunState.CANCELLED) {
                    throw new RunCancelledException();
                }
            }
        };
    }

    private void begin(Run run) {
        runs.update(RunState.QUEUED, run.begin(clock.instant()));
    }

    private void settleRunning(Run run) {
        if (runs.counts(run.runId()).unfinished() == 0) {
            runs.update(RunState.RUNNING, run.complete(clock.instant()));
        }
    }

    private void settleCancelling(Run run) {
        runs.cancelPendingCases(run.runId());
        if (runs.counts(run.runId()).running() == 0) {
            runs.update(RunState.CANCELLING, run.finishCancel(clock.instant()));
        }
    }

    private static String describe(RuntimeException e) {
        var message = e.getMessage();
        return message != null && !message.isBlank() ? message : e.getClass().getSimpleName();
    }
}
