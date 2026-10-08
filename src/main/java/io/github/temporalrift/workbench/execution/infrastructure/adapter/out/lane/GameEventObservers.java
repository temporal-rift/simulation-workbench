package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;

/** Opens the observer of one game's raw events on a lane. */
@FunctionalInterface
public interface GameEventObservers {

    /**
     * @param scopeId the logical case, or reproduction, the evidence belongs to
     * @param attemptId the attempt that retains it
     * @param since when a fresh game began on the lane, so the observer need not read the history of earlier games;
     *     null when the game may already hold events and the observer must resume from what it retained
     */
    GameEventObserver open(LaneEndpoints lane, UUID scopeId, UUID attemptId, UUID gameId, Instant since);
}
