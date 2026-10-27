package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunState;

class RunStartingServiceTest {

    private static final UUID RUN = new UUID(1, 1);

    private final ResumeRunUseCase resumeRun = mock(ResumeRunUseCase.class);
    private final RunStartingService service = new RunStartingService(mock(StartRunUseCase.class), resumeRun);

    @Test
    void everyResumeIsItsOwnCommand() {
        service.resume(RUN);
        service.resume(RUN);

        var commands = ArgumentCaptor.forClass(ResumeRunUseCase.Command.class);
        verify(resumeRun, org.mockito.Mockito.times(2)).handle(commands.capture());
        assertThat(commands.getAllValues())
                .extracting(ResumeRunUseCase.Command::runId)
                .containsOnly(RUN);
        assertThat(commands.getAllValues())
                .extracting(ResumeRunUseCase.Command::idempotencyKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void aRunThatIsNotInterruptedIsRefused() {
        doThrow(new InvalidRunStateException("resume", RunState.RUNNING))
                .when(resumeRun)
                .handle(any());

        assertThatThrownBy(() -> service.resume(RUN)).isInstanceOf(ExecutionRefusedException.class);
    }
}
