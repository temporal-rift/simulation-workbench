package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;

/**
 * An acquired isolated lane. Closing it closes the case's execution context and returns the lane, which is offered to
 * another case only once its services hold no context of this one.
 */
public interface CaseLane extends AutoCloseable {

    String laneId();

    /** Opens the case's execution context on the lane's services and starts its game. */
    GameSession open(CaseContext context);

    @Override
    void close();

    /** Everything the lane needs to start a case reproducibly. */
    record CaseContext(
            UUID caseId,
            UUID attemptId,
            UUID caseKey,
            String seed,
            String manifestDigest,
            CaseRules rules,
            List<SeatPlan> seats,
            Instant logicalEpoch) {
        public CaseContext {
            seats = List.copyOf(seats);
        }
    }
}
