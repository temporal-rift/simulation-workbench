package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Optional;

/** Hands out isolated service lanes, at most one case per lane at a time. */
public interface LaneProvider {

    /**
     * Acquires a free lane, preferring {@code preferredLaneId} (the lane a recovering attempt last played
     * on), or returns empty when every lane is busy.
     */
    Optional<CaseLane> acquire(String preferredLaneId);

    /** Whether a lane is free right now, so a worker claims a case only when it can play it. */
    boolean hasFreeLane();
}
