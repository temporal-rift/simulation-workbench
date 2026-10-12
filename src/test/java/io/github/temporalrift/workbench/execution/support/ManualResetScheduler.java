package io.github.temporalrift.workbench.execution.support;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ResetScheduler;

/** Holds scheduled resets until the test runs them, so it can observe a lane between two tries. */
public class ManualResetScheduler implements ResetScheduler {

    private final Deque<Runnable> pending = new ArrayDeque<>();
    private final List<Duration> delays = new ArrayList<>();

    @Override
    public synchronized void schedule(Runnable task, Duration delay) {
        pending.add(task);
        delays.add(delay);
    }

    /** Runs the tasks scheduled so far, but not the retries they schedule; returns how many ran. */
    public int runPending() {
        List<Runnable> due;
        synchronized (this) {
            due = new ArrayList<>(pending);
            pending.clear();
        }
        due.forEach(Runnable::run);
        return due.size();
    }

    /** Runs scheduled tasks, and the retries they schedule, until none is left. */
    public void runAll() {
        while (runPending() > 0) {
            // A failed try schedules its retry, which the next round runs.
        }
    }

    /** Forgets the recorded delays. */
    public synchronized void clear() {
        delays.clear();
    }

    public synchronized boolean idle() {
        return pending.isEmpty();
    }

    public synchronized List<Duration> delays() {
        return List.copyOf(delays);
    }
}
