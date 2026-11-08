package io.github.temporalrift.workbench.search.domain.campaign;

/** The simulator a confirmation plays on is not enabled, or refuses what the confirmation needs. */
public class SimulatorNotAvailableException extends RuntimeException {

    public SimulatorNotAvailableException(String message) {
        super(message);
    }

    public SimulatorNotAvailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
