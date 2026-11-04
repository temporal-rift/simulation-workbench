package io.github.temporalrift.workbench.search.domain.port.out;

/** Driven port to the fidelity module: whether the simulator may be searched on. */
public interface FidelityGate {

    /** Whether a passing fidelity check covers exactly the source revisions the simulator embeds now. */
    boolean established();
}
