package io.github.temporalrift.workbench.fidelity;

/**
 * Whether the simulator plays the same game as the real services. A record applies only to the exact source
 * revisions it ran with, so this is false as soon as the simulator embeds a revision no passing check covers, and
 * whenever the simulator is not enabled.
 */
public interface SimulatorFidelityGate {

    /** Whether a passing fidelity check exists for exactly the source revisions the simulator embeds now. */
    boolean established();
}
