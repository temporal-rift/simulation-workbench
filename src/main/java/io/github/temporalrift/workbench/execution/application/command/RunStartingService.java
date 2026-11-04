package io.github.temporalrift.workbench.execution.application.command;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.execution.application.port.in.CancelRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.SetRunConcurrencyUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.execution.domain.run.UnservedVariantsException;

/** Starts runs for other modules through the same use case the REST operation uses. */
public class RunStartingService implements RunStarter {

    private final StartRunUseCase startRun;
    private final ResumeRunUseCase resumeRun;
    private final CancelRunUseCase cancelRun;
    private final SetRunConcurrencyUseCase setConcurrency;

    public RunStartingService(
            StartRunUseCase startRun,
            ResumeRunUseCase resumeRun,
            CancelRunUseCase cancelRun,
            SetRunConcurrencyUseCase setConcurrency) {
        this.startRun = startRun;
        this.resumeRun = resumeRun;
        this.cancelRun = cancelRun;
        this.setConcurrency = setConcurrency;
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

    @Override
    public void setConcurrency(UUID runId, int concurrency) {
        try {
            setConcurrency.handle(new SetRunConcurrencyUseCase.Command(runId, concurrency));
        } catch (InvalidRunStateException | RunNotFoundException | IllegalArgumentException e) {
            throw new ExecutionRefusedException(e.getMessage(), e);
        }
    }

    /** Each cancel is its own command, like each resume; cancelling a finished run is accepted and changes nothing. */
    @Override
    public void cancel(UUID runId) {
        try {
            cancelRun.handle(new CancelRunUseCase.Command(runId, UUID.randomUUID()));
        } catch (RunNotFoundException e) {
            throw new ExecutionRefusedException(e.getMessage(), e);
        }
    }
}
