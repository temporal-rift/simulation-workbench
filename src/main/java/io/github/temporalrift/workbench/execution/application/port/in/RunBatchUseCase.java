package io.github.temporalrift.workbench.execution.application.port.in;

/** The worker side of the runner: recovery, scheduling and execution of pending cases. */
public interface RunBatchUseCase {

    /** Interrupts everything a previous process left running, as on startup. */
    void recoverAfterRestart();

    /** Starts queued runs, interrupts expired leases and settles runs that have no unfinished cases. */
    void maintain();

    /**
     * Claims and executes at most one pending case for {@code owner}.
     *
     * @return whether a case was executed; false means there was nothing to do or no lane was free
     */
    boolean runNextCase(String owner);

    /** Interrupts the attempts {@code owner} still holds so they resume cleanly. */
    void shutdown(String owner);
}
