package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/** The frozen experiments a run executes. */
public interface ExperimentSource {

    /** The execution inputs of a frozen experiment, or empty when it does not exist. */
    Optional<Plan> plan(UUID experimentId);

    /** Every case of the experiment's matrix in stable order, or empty when it does not exist. */
    Optional<List<PlannedCase>> cases(UUID experimentId);

    record Plan(
            String manifestDigest, int concurrency, int caseWallTimeoutSeconds, int maxRejectedCandidatesPerWindow) {}

    record PlannedCase(UUID caseKey, String seed, String variantLabel, int playerCount, List<SeatPlan> seats) {
        public PlannedCase {
            seats = List.copyOf(seats);
        }
    }
}
