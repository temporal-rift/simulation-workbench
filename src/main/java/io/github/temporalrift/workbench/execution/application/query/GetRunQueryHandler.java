package io.github.temporalrift.workbench.execution.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.application.port.in.GetRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;

public class GetRunQueryHandler implements GetRunUseCase {

    private final RunRepository runs;

    public GetRunQueryHandler(RunRepository runs) {
        this.runs = runs;
    }

    @Override
    public RunView handle(UUID runId) {
        var run = runs.find(runId).orElseThrow(() -> new RunNotFoundException("Run", runId));
        return new RunView(run, runs.counts(runId));
    }
}
