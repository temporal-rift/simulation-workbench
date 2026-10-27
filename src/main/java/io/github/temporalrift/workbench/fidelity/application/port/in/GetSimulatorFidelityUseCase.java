package io.github.temporalrift.workbench.fidelity.application.port.in;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.domain.SourceRevisions;

/** Whether the simulator's current source revisions have a passing fidelity record. */
public interface GetSimulatorFidelityUseCase {

    /** @throws io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException when not enabled */
    SimulatorFidelity handle();

    /** @param passingCheck the newest passing check for exactly these revisions; null when not established */
    record SimulatorFidelity(SourceRevisions sourceRevisions, boolean established, FidelityCheckView passingCheck) {}
}
