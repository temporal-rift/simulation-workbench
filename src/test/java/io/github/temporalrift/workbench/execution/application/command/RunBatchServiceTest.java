package io.github.temporalrift.workbench.execution.application.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.execution.domain.run.RunState;

class RunBatchServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID RUN = new UUID(1, 1);

    private final RunRepository runs = mock(RunRepository.class);
    private final CaseLedger cases = mock(CaseLedger.class);
    private final ExperimentSource experiments = mock(ExperimentSource.class);
    private final LaneProvider lanes = mock(LaneProvider.class);
    private final RunBatchService service = new RunBatchService(
            runs,
            cases,
            experiments,
            lanes,
            mock(CaseDriver.class),
            Clock.fixed(NOW, ZoneOffset.UTC),
            new ExecutionSettings(Duration.ofSeconds(30), Duration.ofSeconds(5), 2, NOW));

    @Test
    void aRunWhoseFrozenExperimentIsGoneFailsAndItsPendingCasesAreCancelled() {
        var running = Run.queued(RUN, new UUID(2, 2), NOW).begin(NOW);
        var logicalCase = new LogicalCase(
                new UUID(3, 3), RUN, new UUID(4, 4), 0, "v", "42", 3, List.of(), CaseState.RUNNING, null);
        var attempt =
                new Attempt(new UUID(5, 5), logicalCase.caseId(), 1, AttemptState.RUNNING, null, null, NOW, null, null);
        when(lanes.hasFreeLane()).thenReturn(true);
        when(cases.claimNext(any(), any(), any()))
                .thenReturn(Optional.of(new CaseLedger.Claim(logicalCase, attempt, Optional.empty(), 1)));
        when(runs.find(RUN)).thenReturn(Optional.of(running));
        when(experiments.plan(running.experimentId())).thenReturn(Optional.empty());
        when(runs.update(eq(RunState.RUNNING), any())).thenReturn(true);

        assertThat(service.runNextCase("worker")).isTrue();

        var failed = ArgumentCaptor.forClass(Run.class);
        verify(runs).update(eq(RunState.RUNNING), failed.capture());
        assertThat(failed.getValue().state()).isEqualTo(RunState.FAILED);
        assertThat(failed.getValue().failure().code()).isEqualTo(FailureCode.CONTRACT_MISMATCH);
        verify(cases).release(eq(attempt.attemptId()), eq("worker"), any());
        verify(runs).cancelPendingCases(RUN);
        verify(lanes, never()).acquire(any());
    }
}
