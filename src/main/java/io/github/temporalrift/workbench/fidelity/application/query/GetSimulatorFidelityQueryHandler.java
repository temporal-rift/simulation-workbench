package io.github.temporalrift.workbench.fidelity.application.query;

import io.github.temporalrift.workbench.fidelity.SimulatorFidelityGate;
import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;

/** Answers whether the simulator's current source revisions have a passing record, for the API and for campaigns. */
public class GetSimulatorFidelityQueryHandler implements GetSimulatorFidelityUseCase, SimulatorFidelityGate {

    private final FidelityRepository checks;
    private final FidelityEnvironments environments;

    public GetSimulatorFidelityQueryHandler(FidelityRepository checks, FidelityEnvironments environments) {
        this.checks = checks;
        this.environments = environments;
    }

    @Override
    public SimulatorFidelity handle() {
        var simulator = environments
                .simulator()
                .orElseThrow(() -> new SimulatorNotAvailableException("The simulator is not enabled"));
        var revisions = simulator.environment().sourceRevisions();
        var passing =
                checks.newestPassed(revisions).map(check -> new FidelityCheckView(check, checks.counts(check.id())));
        return new SimulatorFidelity(revisions, passing.isPresent(), passing.orElse(null));
    }

    @Override
    public boolean established() {
        return environments
                .simulator()
                .map(simulator -> checks.newestPassed(simulator.environment().sourceRevisions())
                        .isPresent())
                .orElse(false);
    }
}
