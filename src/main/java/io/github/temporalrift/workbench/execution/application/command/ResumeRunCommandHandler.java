package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

/**
 * Re-queues an interrupted run. Only {@code INTERRUPTED} may resume; the pending cases are exactly the
 * unfinished ones, so completed results are never scheduled or counted again.
 */
public class ResumeRunCommandHandler implements ResumeRunUseCase {

    static final String OPERATION = "resumeRun";

    private final RunRepository runs;
    private final Clock clock;

    public ResumeRunCommandHandler(RunRepository runs, Clock clock) {
        this.runs = runs;
        this.clock = clock;
    }

    @Override
    public RunView handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var requestHash = RunRequestHash.of(OPERATION, command.runId());
        var claim = runs.findCommand(command.idempotencyKey());
        if (claim.isPresent()) {
            if (!OPERATION.equals(claim.get().operation())
                    || !claim.get().runId().equals(command.runId())
                    || !claim.get().requestHash().equals(requestHash)) {
                throw new RunIdempotencyConflictException();
            }
            return view(command.runId());
        }
        var run = runs.find(command.runId()).orElseThrow(() -> new RunNotFoundException("Run", command.runId()));
        var resumed = run.resume();
        if (!runs.update(run.state(), resumed)) {
            var current = runs.find(command.runId()).orElseThrow();
            throw new InvalidRunStateException("resume", current.state());
        }
        runs.claimCommand(command.idempotencyKey(), OPERATION, command.runId(), requestHash, clock.instant());
        return view(command.runId());
    }

    private RunView view(UUID runId) {
        var run = runs.find(runId).orElseThrow(() -> new RunNotFoundException("Run", runId));
        return new RunView(run, runs.counts(runId));
    }
}
