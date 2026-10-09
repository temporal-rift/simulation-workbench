package io.github.temporalrift.workbench.execution;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Public API of the execution module: what other modules may read of runs, their cases and evidence. */
public interface RunCatalog {

    /** The run, or empty for an unknown run. */
    Optional<RunSummary> run(UUID runId);

    /** Every logical case of the run in matrix order. */
    List<RunCase> cases(UUID runId);

    /** The retained evidence of a succeeded case's counting game, or empty when the case has not succeeded. */
    Optional<CaseEvidence> evidence(UUID runId, UUID caseId);
}
