package io.github.temporalrift.workbench.fidelity.application.query;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariantCatalog;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;

/**
 * Answers whether the simulator's current source revisions have a passing record on the parameter space new checks use.
 * A check that passed on another space does not establish it. The answer is for the operator to read; nothing in the
 * workbench waits for it.
 */
public class GetSimulatorFidelityQueryHandler implements GetSimulatorFidelityUseCase {

    private final FidelityRepository checks;
    private final FidelityEnvironments environments;
    private final EdgeVariantCatalog edgeVariantCatalog;

    public GetSimulatorFidelityQueryHandler(
            FidelityRepository checks, FidelityEnvironments environments, EdgeVariantCatalog edgeVariantCatalog) {
        this.checks = checks;
        this.environments = environments;
        this.edgeVariantCatalog = edgeVariantCatalog;
    }

    @Override
    public SimulatorFidelity handle() {
        var simulator = environments
                .simulator()
                .orElseThrow(() -> new SimulatorNotAvailableException("The simulator is not enabled"));
        var revisions = simulator.environment().sourceRevisions();
        var space = edgeVariantCatalog.newest().parameterSpaceVersion();
        var passing = checks.newestPassed(revisions, space)
                .map(check -> new FidelityCheckView(check, checks.counts(check.id())));
        return new SimulatorFidelity(revisions, passing.isPresent(), passing.orElse(null));
    }
}
