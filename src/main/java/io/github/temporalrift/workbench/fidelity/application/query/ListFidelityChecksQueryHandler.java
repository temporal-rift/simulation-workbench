package io.github.temporalrift.workbench.fidelity.application.query;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityChecksUseCase;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.shared.Page;

public class ListFidelityChecksQueryHandler implements ListFidelityChecksUseCase {

    private final FidelityRepository checks;

    public ListFidelityChecksQueryHandler(FidelityRepository checks) {
        this.checks = checks;
    }

    @Override
    public Page<FidelityCheckView> handle(Query query) {
        var page = checks.list(query.state(), query.limit(), query.offset());
        return new Page<>(
                page.items().stream()
                        .map(check -> new FidelityCheckView(check, checks.counts(check.id())))
                        .toList(),
                page.total());
    }
}
