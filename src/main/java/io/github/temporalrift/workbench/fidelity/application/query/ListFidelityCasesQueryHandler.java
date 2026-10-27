package io.github.temporalrift.workbench.fidelity.application.query;

import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityCasesUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckNotFoundException;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.shared.Page;

public class ListFidelityCasesQueryHandler implements ListFidelityCasesUseCase {

    private final FidelityRepository checks;

    public ListFidelityCasesQueryHandler(FidelityRepository checks) {
        this.checks = checks;
    }

    @Override
    public Page<FidelityCase> handle(Query query) {
        if (checks.find(query.checkId()).isEmpty()) {
            throw new FidelityCheckNotFoundException(query.checkId());
        }
        return checks.cases(query.checkId(), query.variant(), query.result(), query.limit(), query.offset());
    }
}
