package io.github.temporalrift.workbench.execution.domain.run;

/**
 * The seat no longer owes a decision in the window that was observed, because the window closed in the
 * meantime. The driver re-reads where the game stands instead of acting on a stale window.
 */
public class WindowClosedException extends RuntimeException {

    public WindowClosedException(int seatIndex) {
        super("Seat " + seatIndex + " no longer owes a decision in the observed window");
    }
}
