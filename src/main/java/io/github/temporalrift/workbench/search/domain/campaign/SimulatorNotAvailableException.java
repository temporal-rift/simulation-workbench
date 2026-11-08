package io.github.temporalrift.workbench.search.domain.campaign;

/** The simulator a confirmation plays on is not enabled. */
public class SimulatorNotAvailableException extends RuntimeException {

    public SimulatorNotAvailableException(String message) {
        super(message);
    }
}
