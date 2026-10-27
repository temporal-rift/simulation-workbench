package io.github.temporalrift.workbench.fidelity.domain;

/** The simulator is not enabled or no real lane is build-equivalent to it (published as SIMULATOR_NOT_AVAILABLE). */
public class SimulatorNotAvailableException extends RuntimeException {

    public SimulatorNotAvailableException(String reason) {
        super(reason);
    }
}
