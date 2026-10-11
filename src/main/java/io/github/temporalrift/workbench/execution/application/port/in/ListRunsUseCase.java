package io.github.temporalrift.workbench.execution.application.port.in;

import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.shared.Page;

/** Lists runs newest first, optionally of one experiment and one state. */
public interface ListRunsUseCase {

    Page<RunView> handle(Query query);

    /** A null {@code experimentId} or {@code state} leaves that filter out. */
    record Query(UUID experimentId, RunState state, int limit, int offset) {}
}
