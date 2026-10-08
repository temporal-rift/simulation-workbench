package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;

/**
 * Drives the runner: on start it interrupts whatever a previous process left running, then keeps one
 * maintenance loop and a pool of workers going, each taking the next pending case or else the next queued
 * reproduction. On stop it interrupts the attempts it still holds so
 * the designer can resume them.
 */
public class BatchWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(BatchWorker.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final RunBatchUseCase batch;
    private final RunReproductionUseCase reproductions;
    private final int workers;
    private final Duration pollInterval;
    private final String owner = "workbench-" + UUID.randomUUID();
    private final AtomicBoolean running = new AtomicBoolean();
    private final List<Thread> threads = new ArrayList<>();

    public BatchWorker(
            RunBatchUseCase batch, RunReproductionUseCase reproductions, int workers, Duration pollInterval) {
        this.batch = batch;
        this.reproductions = reproductions;
        this.workers = workers;
        this.pollInterval = pollInterval;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        batch.recoverAfterRestart();
        reproductions.recoverAfterRestart();
        threads.add(loop("maintenance", () -> {
            batch.maintain();
            reproductions.maintain();
        }));
        for (var worker = 0; worker < workers; worker++) {
            threads.add(loop("case-worker-" + worker, () -> {
                if (!batch.runNextCase(owner) && !reproductions.runNext(owner)) {
                    pause();
                }
            }));
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        threads.forEach(Thread::interrupt);
        var deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(SHUTDOWN_WAIT.toMillis());
        for (var thread : threads) {
            try {
                thread.join(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            } catch (InterruptedException _) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        threads.clear();
        batch.shutdown(owner);
        reproductions.shutdown(owner);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private Thread loop(String name, Runnable step) {
        var thread = new Thread(
                () -> {
                    while (running.get() && !Thread.currentThread().isInterrupted()) {
                        try {
                            step.run();
                            if (name.equals("maintenance")) {
                                pause();
                            }
                        } catch (RuntimeException e) {
                            LOG.error("{} step failed; retrying after the poll interval", name, e);
                            pause();
                        }
                    }
                },
                name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private void pause() {
        try {
            Thread.sleep(pollInterval);
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }
}
