package io.github.temporalrift.workbench.execution.application.command;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.execution.domain.run.UnservedVariantsException;

/** Starts runs for other modules through the same use case the REST operation uses. */
public class RunStartingService implements RunStarter {

    private final StartRunUseCase startRun;
    private final ResumeRunUseCase resumeRun;

    public RunStartingService(StartRunUseCase startRun, ResumeRunUseCase resumeRun) {
        this.startRun = startRun;
        this.resumeRun = resumeRun;
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

    /** Each resume is its own command: an earlier resume of the run must not answer for a later interruption. */
    @Override
    public void resume(UUID runId) {
        try {
            resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID()));
        } catch (InvalidRunStateException | RunNotFoundException e) {
            throw new ExecutionRefusedException(e.getMessage(), e);
        }
    }
}
