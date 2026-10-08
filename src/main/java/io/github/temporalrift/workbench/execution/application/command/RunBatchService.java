package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LeaseLostException;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCancelledException;
import io.github.temporalrift.workbench.execution.domain.run.RunState;

/**
 * Executes the pending cases of running runs. A claim opens a new attempt that owns the case through a
 * renewable lease; every outcome is fenced by that lease, so an interrupted or stalled worker can never
 * overwrite a case another attempt recovered, and a logical case is recorded as succeeded at most once.
 */
public class RunBatchService implements RunBatchUseCase {

    private final RunRepository runs;
    private final CaseLedger cases;
    private final ExperimentSource experiments;
    private final LaneProvider lanes;
    private final CaseDriver driver;
    private final Clock clock;
    private final ExecutionSettings settings;

    public RunBatchService(
            RunRepository runs,
            CaseLedger cases,
            CommandLedger commands,
            ExperimentSource experiments,
            LaneProvider lanes,
            DecisionRuntime decisions,
            Clock clock,
            ExecutionSettings settings) {
        this.runs = runs;
        this.cases = cases;
        this.experiments = experiments;
        this.lanes = lanes;
        this.driver = new CaseDriver(decisions, commands, cases, clock, settings);
        this.clock = clock;
        this.settings = settings;
    }

    @Override
    public void recoverAfterRestart() {
        cases.interruptAllRunning(clock.instant());
    }

    @Override
    public void maintain() {
        cases.interruptExpired(clock.instant());
        runs.findByState(RunState.QUEUED).forEach(this::begin);
        runs.findByState(RunState.RUNNING).forEach(this::settleRunning);
        runs.findByState(RunState.CANCELLING).forEach(this::settleCancelling);
    }

    @Override
    public boolean runNextCase(String owner) {
        if (!lanes.hasFreeLane()) {
            return false;
        }
        var now = clock.instant();
        var claim = cases.claimNext(owner, now, now.plus(settings.lease()));
        if (claim.isEmpty()) {
            return false;
        }
        var attempt = claim.get().attempt();
        var previous = claim.get().previous().orElse(null);
        var experimentId = runs.find(claim.get().logicalCase().runId())
                .map(Run::experimentId)
                .orElseThrow();
        var plan = experiments.plan(experimentId);
        if (plan.isEmpty()) {
            fail(attempt, owner, new Failure(FailureCode.CONTRACT_MISMATCH, "The frozen experiment is unavailable"));
            return true;
        }
        var lane = lanes.acquire(previous == null ? null : previous.laneId());
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
        var attempt = claim.attempt();
        try (lane) {
            var result = driver.play(
                    claim.logicalCase(), attempt, previous, plan, lane, guard(attempt.attemptId(), claim, owner));
            cases.succeed(attempt.attemptId(), owner, result, clock.instant());
        } catch (AttemptFailedException e) {
            fail(attempt, owner, e.failure());
        } catch (RunCancelledException _) {
            cases.cancel(attempt.attemptId(), owner, clock.instant());
        } catch (LeaseLostException _) {
            // Another attempt owns the case now; nothing this worker holds is left to settle.
        } catch (RuntimeException e) {
            fail(attempt, owner, new Failure(FailureCode.EXECUTION_FAILED, describe(e)));
        }
    }

    private void fail(Attempt attempt, String owner, Failure failure) {
        var retry = failure.code().isRetryable() && attempt.ordinal() < settings.maxAttemptsPerCase();
        cases.fail(attempt.attemptId(), owner, failure, retry, clock.instant());
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
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }
}
