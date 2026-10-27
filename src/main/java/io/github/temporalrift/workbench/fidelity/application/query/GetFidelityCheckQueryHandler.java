package io.github.temporalrift.workbench.fidelity.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckNotFoundException;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;

public class GetFidelityCheckQueryHandler implements GetFidelityCheckUseCase {

    private final FidelityRepository checks;

    public GetFidelityCheckQueryHandler(FidelityRepository checks) {
        this.checks = checks;
    }

    @Override
    public FidelityCheckView handle(UUID checkId) {
        var check = checks.find(checkId).orElseThrow(() -> new FidelityCheckNotFoundException(checkId));
        return new FidelityCheckView(check, checks.counts(checkId));
    }
}
