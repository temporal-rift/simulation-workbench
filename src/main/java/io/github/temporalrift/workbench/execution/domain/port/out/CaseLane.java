package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/** An acquired isolated lane. Closing it returns the lane to the pool. */
public interface CaseLane extends AutoCloseable {

    String laneId();

    /**
     * Configures the lane for the case and starts its game, or attaches to {@code resumeGameId} when the
     * lane still hosts that game for the same case. A lane that cannot continue the game is started afresh.
     */
    GameSession open(CaseContext context, UUID resumeGameId);

    @Override
    void close();

    /** Everything the lane needs to start a case reproducibly. */
    record CaseContext(
            UUID caseId,
            UUID attemptId,
            UUID caseKey,
            String seed,
            String manifestDigest,
            List<SeatPlan> seats,
            Instant logicalEpoch) {
        public CaseContext {
            seats = List.copyOf(seats);
        }
    }
}
