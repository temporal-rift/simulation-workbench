package io.github.temporalrift.workbench.execution.application.command;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.run.UnservedVariantsException;

/** Starts runs for other modules through the same use case the REST operation uses. */
public class RunStartingService implements RunStarter {

    private final StartRunUseCase startRun;

    public RunStartingService(StartRunUseCase startRun) {
        this.startRun = startRun;
    }

    @Override
    public UUID start(UUID experimentId, UUID idempotencyKey) {
        try {
            return startRun.handle(new StartRunUseCase.Command(experimentId, idempotencyKey))
                    .run()
                    .runId();
        } catch (UnservedVariantsException e) {
            throw new ExecutionRefusedException(e.getMessage(), e);
        }
    }
}
