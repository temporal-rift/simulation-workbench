package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.scheduler;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.temporalrift.workbench.fidelity.application.port.in.AdvanceFidelityUseCase;

/**
 * Keeps the open fidelity check moving: one thread advances it a step at a time and pauses when a step changed nothing,
 * because the run or the reproductions are still being played by the execution workers. A restart resumes from what
 * the check has persisted.
 */
public class FidelityWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(FidelityWorker.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final AdvanceFidelityUseCase fidelity;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public FidelityWorker(AdvanceFidelityUseCase fidelity, Duration pollInterval) {
        this.fidelity = fidelity;
        this.pollInterval = pollInterval;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, "fidelity-worker");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        thread.interrupt();
        try {
            thread.join(TimeUnit.MILLISECONDS.toMillis(SHUTDOWN_WAIT.toMillis()));
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!fidelity.advance()) {
                    pause();
                }
            } catch (RuntimeException e) {
                LOG.error("A fidelity step failed; retrying after the poll interval", e);
                pause();
            }
        }
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
