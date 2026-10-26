package io.github.temporalrift.workbench.execution.domain.run;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.execution.CaseState;

/**
 * One logical case of a run. It contributes at most one result however many attempts executed or
 * recovered it; {@code result} is present only once {@code state} is {@link CaseState#SUCCEEDED}. It plays only on
 * a lane that fits {@code fit}; {@code bundle} holds its variant's frozen rules and content digests.
 */
public record LogicalCase(
        UUID caseId,
        UUID runId,
        UUID caseKey,
        int ordinal,
        String variantLabel,
        BundleDigests bundle,
        LaneFit fit,
        String seed,
        int playerCount,
        List<SeatPlan> seats,
        CaseState state,
        CaseResult result) {

    public LogicalCase {
        seats = List.copyOf(seats);
    }
}
