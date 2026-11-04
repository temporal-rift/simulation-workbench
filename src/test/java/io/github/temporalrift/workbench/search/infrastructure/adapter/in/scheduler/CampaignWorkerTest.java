package io.github.temporalrift.workbench.search.infrastructure.adapter.in.scheduler;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.search.application.port.in.AdvanceCampaignsUseCase;

class CampaignWorkerTest {

    @Test
    void theWorkerRecoversOnceThenKeepsAdvancingAndStopsCleanly() {
        var recovered = new AtomicInteger();
        var advanced = new AtomicInteger();
        var worker = new CampaignWorker(
                new AdvanceCampaignsUseCase() {
                    @Override
                    public boolean advance() {
                        return advanced.incrementAndGet() % 2 == 0;
                    }

                    @Override
                    public void recover() {
                        recovered.incrementAndGet();
                    }
                },
                Duration.ofMillis(5));

        worker.start();
        awaitAdvances(advanced, 5);
        worker.stop();

        assertThat(worker.isRunning()).isFalse();
        assertThat(recovered).hasValue(1);
    }

    @Test
    void aStepThatFailsIsRetriedAfterThePollInterval() {
        var advanced = new AtomicInteger();
        var worker = new CampaignWorker(
                new AdvanceCampaignsUseCase() {
                    @Override
                    public boolean advance() {
                        if (advanced.incrementAndGet() == 1) {
                            throw new IllegalStateException("boom");
                        }
                        return false;
                    }

                    @Override
                    public void recover() {
                        throw new IllegalStateException("recovery failed");
                    }
                },
                Duration.ofMillis(5));

        worker.start();
        awaitAdvances(advanced, 3);
        worker.stop();

        assertThat(advanced.get()).isGreaterThanOrEqualTo(3);
    }

    private static void awaitAdvances(AtomicInteger advanced, int count) {
        var deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (advanced.get() < count && System.nanoTime() < deadline) {
            LockSupport.parkNanos(Duration.ofMillis(5).toNanos());
        }
        assertThat(advanced.get()).isGreaterThanOrEqualTo(count);
    }
}
