package io.github.temporalrift.workbench.fidelity.application.port.in;

import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.shared.Page;

/** Lists fidelity checks newest first. */
public interface ListFidelityChecksUseCase {

    Page<FidelityCheckView> handle(Query query);

    /** @param state only checks in this state when given */
    record Query(FidelityCheckState state, int limit, int offset) {}
}
