package io.github.temporalrift.workbench.execution.application.command;

import io.github.temporalrift.workbench.execution.application.port.in.SetRunConcurrencyUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

/**
 * Sets the concurrency bound a claim enforces. Cases already executing are untouched, so lowering the bound never
 * discards a game; it only keeps the next ones from starting until the bound allows them.
 */
public class SetRunConcurrencyCommandHandler implements SetRunConcurrencyUseCase {

    private final RunRepository runs;

    public SetRunConcurrencyCommandHandler(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    public void handle(Command command) {
        if (runs.setConcurrency(command.runId(), command.concurrency())) {
            return;
        }
        var run = runs.find(command.runId()).orElseThrow(() -> new RunNotFoundException("Run", command.runId()));
        throw new InvalidRunStateException("setConcurrency", run.state());
    }
}
