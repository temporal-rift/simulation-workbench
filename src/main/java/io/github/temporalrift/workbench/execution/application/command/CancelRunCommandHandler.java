package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.CancelRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunCommand;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.execution.domain.run.RunState;

/**
 * Stops scheduling new cases: pending cases are cancelled at once, running ones are signalled through
 * the run state, and every completed result is kept. Repeating a cancellation never reopens a run.
 */
public class CancelRunCommandHandler implements CancelRunUseCase {

    static final String OPERATION = "cancelRun";
    private static final int MAX_RACES = 5;

    private final RunRepository runs;
    private final Clock clock;

    public CancelRunCommandHandler(RunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    public Result handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var requestHash = RunRequestHash.of(OPERATION, command.runId());
        var claim = runs.findCommand(command.idempotencyKey());
        if (claim.isPresent()) {
            verify(claim.get(), command.runId(), requestHash);
            return new Result(view(command.runId()), true);
        }
        for (var race = 0; race < MAX_RACES; race++) {
            var run = runs.find(command.runId()).orElseThrow(() -> new RunNotFoundException("Run", command.runId()));
            if (run.state().isTerminal()) {
                return new Result(view(run), false);
            }
            if (run.state() == RunState.CANCELLING) {
                return new Result(view(run), true);
            }
            if (runs.update(run.state(), run.requestCancel())) {
                return accepted(command, requestHash);
            }
        }
        throw new IllegalStateException("Run " + command.runId() + " kept changing while it was being cancelled");
    }

    private Result accepted(Command command, String requestHash) {
        var now = clock.instant();
        runs.claimCommand(command.idempotencyKey(), OPERATION, command.runId(), requestHash, now);
        runs.cancelPendingCases(command.runId());
        settle(command.runId());
        return new Result(view(command.runId()), true);
    }

    /** With no case still running the cancellation completes immediately; otherwise the worker finishes it. */
    private void settle(UUID runId) {
        if (runs.counts(runId).running() == 0) {
            runs.find(runId)
                    .filter(run -> run.state() == RunState.CANCELLING)
                    .ifPresent(run -> runs.update(RunState.CANCELLING, run.finishCancel(clock.instant())));
        }
    }

    private static void verify(RunCommand claim, UUID runId, String requestHash) {
        if (!OPERATION.equals(claim.operation())
                || !claim.runId().equals(runId)
                || !claim.requestHash().equals(requestHash)) {
            throw new RunIdempotencyConflictException();
        }
    }

    private RunView view(UUID runId) {
        return view(runs.find(runId).orElseThrow(() -> new RunNotFoundException("Run", runId)));
    }

    private RunView view(Run run) {
        return new RunView(run, runs.counts(run.runId()));
    }
}
