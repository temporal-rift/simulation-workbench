package io.github.temporalrift.workbench.fidelity.application.port.in;

/** The worker side of fidelity checks: moves the open check forward by one step. */
public interface AdvanceFidelityUseCase {

    /** @return whether the step changed something; false means there was nothing to do or nothing is ready yet */
    boolean advance();
}
