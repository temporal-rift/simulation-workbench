package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.shared.Page;

/** Lists the cases of one run in matrix order, optionally of one state and one variant. */
public interface ListCasesUseCase {

    Page<LogicalCase> handle(Query query);

    /** A null {@code state} or {@code variantLabel} leaves that filter out. */
    record Query(UUID runId, CaseState state, String variantLabel, int limit, int offset) {}
}
