package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.scheduler;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;

class BatchWorkerTest {

    private static final Duration POLL = Duration.ofMillis(5);

    private final RunBatchUseCase batch = mock(RunBatchUseCase.class);
    private final RunReproductionUseCase reproductions = mock(RunReproductionUseCase.class);

    @Test
    void startRecoversAndKeepsMaintainingUntilStopped() {
        var worker = new BatchWorker(batch, reproductions, 1, POLL);

        worker.start();
        verify(batch).recoverAfterRestart();
        verify(reproductions).recoverAfterRestart();
        verify(batch, timeout(2000).atLeast(2)).maintain();
        verify(reproductions, timeout(2000).atLeast(2)).maintain();
        worker.stop();

        verify(batch).shutdown(anyString());
        verify(reproductions).shutdown(anyString());
    }

    @Test
    void aWorkerWithNothingClaimedPollsBothQueuesAfterEachPause() {
        var worker = new BatchWorker(batch, reproductions, 1, POLL);

        worker.start();
        verify(batch, timeout(2000).atLeast(2)).runNextCase(anyString());
        verify(reproductions, timeout(2000).atLeast(2)).runNext(anyString());
        worker.stop();
    }

    @Test
    void aClaimedCaseIsFollowedImmediatelyWithoutTryingReproductions() {
        when(batch.runNextCase(anyString())).thenReturn(true);
        var worker = new BatchWorker(batch, reproductions, 1, Duration.ofSeconds(30));

        worker.start();
        verify(batch, timeout(2000).atLeast(5)).runNextCase(anyString());
        worker.stop();

        verify(reproductions, after(0).never()).runNext(anyString());
    }

    @Test
    void aFailingStepIsRetriedAfterThePause() {
        when(batch.runNextCase(anyString()))
                .thenThrow(new IllegalStateException("boom"))
                .thenReturn(false);
        var worker = new BatchWorker(batch, reproductions, 1, POLL);

        worker.start();
        verify(batch, timeout(2000).atLeast(2)).runNextCase(anyString());
        worker.stop();
    }

    @Test
    void startingAndStoppingTwiceActsOnce() {
        var worker = new BatchWorker(batch, reproductions, 0, POLL);

        worker.start();
        worker.start();
        worker.stop();
        worker.stop();

        verify(batch, atLeast(1)).recoverAfterRestart();
        verify(batch).shutdown(anyString());
    }
}
