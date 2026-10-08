package io.github.temporalrift.workbench.execution.application.port.in;

/** The worker side of reproductions: recovery and execution of queued reproductions. */
public interface RunReproductionUseCase {

    /** Queues again whatever a previous process left running, as on startup. */
    void recoverAfterRestart();

    /** Queues again the reproductions whose lease expired. */
    void maintain();

    /**
     * Claims and executes at most one queued reproduction for {@code owner}.
     *
     * @return whether a reproduction was executed; false means there was nothing to do or no lane was free
     */
    boolean runNext(String owner);

    /** Queues again the reproductions {@code owner} still holds. */
    void shutdown(String owner);
}
