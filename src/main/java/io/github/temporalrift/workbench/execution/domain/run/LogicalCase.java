package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;
import java.util.UUID;

/**
 * One logical case of a run. It contributes at most one result however many attempts executed or
 * recovered it; {@code result} is present only once {@code state} is {@link CaseState#SUCCEEDED}.
 */
public record LogicalCase(
        UUID caseId,
        UUID runId,
        UUID caseKey,
        int ordinal,
        String variantLabel,
        String seed,
        int playerCount,
        List<SeatPlan> seats,
        CaseState state,
        CaseResult result) {

    public LogicalCase {
        seats = List.copyOf(seats);
    }
}
