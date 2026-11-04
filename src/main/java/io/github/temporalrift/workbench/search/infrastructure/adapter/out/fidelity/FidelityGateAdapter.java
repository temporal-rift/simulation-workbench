package io.github.temporalrift.workbench.search.infrastructure.adapter.out.fidelity;

import io.github.temporalrift.workbench.fidelity.SimulatorFidelityGate;
import io.github.temporalrift.workbench.search.domain.port.out.FidelityGate;

/** Asks the fidelity module whether the simulator may be searched on. */
public class FidelityGateAdapter implements FidelityGate {

    private final SimulatorFidelityGate gate;

    public FidelityGateAdapter(SimulatorFidelityGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean established() {
        return gate.established();
    }
}
