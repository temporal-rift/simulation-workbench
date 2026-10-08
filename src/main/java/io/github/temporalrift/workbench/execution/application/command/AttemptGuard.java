package io.github.temporalrift.workbench.execution.application.command;

/** Keeps a running attempt honest: it renews the lease and stops the attempt when it must not continue. */
interface AttemptGuard {

    /**
     * Called between steps of the game.
     *
     * @throws io.github.temporalrift.workbench.execution.domain.run.LeaseLostException when another worker
     *     owns the case now
     * @throws io.github.temporalrift.workbench.execution.domain.run.RunCancelledException when the run is
     *     being cancelled
     */
    void checkpoint();
}
