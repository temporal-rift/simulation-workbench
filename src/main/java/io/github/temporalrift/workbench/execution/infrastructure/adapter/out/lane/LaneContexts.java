package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.LaneService;

/** Closes execution contexts on the services of a lane, so the lane can take another case. */
public interface LaneContexts {

    /**
     * Returns once none of the given services holds a context of the case; throws when one still may.
     *
     * @param services the services the case's context was sent to
     */
    void close(String laneId, UUID caseKey, Set<LaneService> services);

    /** Returns once no service of the lane holds any context, whichever case opened it; throws when one still may. */
    void closeAnyOpen(String laneId);
}
