package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.UUID;

/** Asks the deployment that owns a lane to make it clean for another case. */
@FunctionalInterface
public interface LaneResets {

    /**
     * Returns once the deployment answered that the lane is clean; throws when it did not.
     *
     * @param caseKey the case the lane hosted, or {@code null} when it is not known
     * @param idempotencyKey identical across the retries of one release
     */
    void reset(String laneId, UUID caseKey, UUID idempotencyKey);
}
