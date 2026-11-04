package io.github.temporalrift.workbench.search.infrastructure.adapter.in.scheduler;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

import io.github.temporalrift.workbench.search.application.port.in.AdvanceCampaignsUseCase;

/**
 * Keeps the campaigns moving: one thread advances them a step at a time and pauses when a step changed nothing,
 * because the games are still being played by the execution workers. Starting counts the running campaigns active
 * from now, so the time the process was down is not charged to their budget, and then resumes from what they have
 * persisted.
 */
public class CampaignWorker implements SmartLifecycle {

    private static final Logger LOG = LoggerFactory.getLogger(CampaignWorker.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofSeconds(10);

    private final AdvanceCampaignsUseCase campaigns;
    private final Duration pollInterval;
    private final AtomicBoolean running = new AtomicBoolean();
    private Thread thread;

    public CampaignWorker(AdvanceCampaignsUseCase campaigns, Duration pollInterval) {
        this.campaigns = campaigns;
        this.pollInterval = pollInterval;
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        thread = new Thread(this::loop, "campaign-worker");
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
            thread.join(SHUTDOWN_WAIT.toMillis());
        } catch (InterruptedException _) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void loop() {
        recover();
        while (running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                if (!campaigns.advance()) {
                    pause();
                }
            } catch (RuntimeException e) {
                LOG.error("A campaign step failed; retrying after the poll interval", e);
                pause();
            }
        }
    }

    private void recover() {
        try {
            campaigns.recover();
        } catch (RuntimeException e) {
            LOG.error("Recovering the running campaigns failed; continuing", e);
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
