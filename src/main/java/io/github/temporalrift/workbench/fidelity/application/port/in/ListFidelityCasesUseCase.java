package io.github.temporalrift.workbench.fidelity.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.shared.Page;

/** Lists the cases of a fidelity check in matrix order. */
public interface ListFidelityCasesUseCase {

    /** @throws io.github.temporalrift.workbench.fidelity.domain.FidelityCheckNotFoundException when unknown */
    Page<FidelityCase> handle(Query query);

    /** @param variant only cases of this variant when given; {@code result} only cases with this result when given */
    record Query(UUID checkId, VariantLabel variant, FidelityCaseResult result, int limit, int offset) {}
}
