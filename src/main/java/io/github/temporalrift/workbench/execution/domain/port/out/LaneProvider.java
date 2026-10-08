package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Optional;
import java.util.Set;

/** Hands out isolated service lanes, at most one case per lane at a time. */
public interface LaneProvider {

    /**
     * Acquires a free lane, preferring {@code preferredLaneId} (the lane a recovering attempt last played
     * on), or returns empty when every lane is busy.
     */
    Optional<CaseLane> acquire(String preferredLaneId);

    /**
     * Acquires exactly this lane, or returns empty when it is busy or not configured. A reproduction needs the
     * lane that played the case: its bot identities are part of what the seats observe.
     */
    Optional<CaseLane> acquireExactly(String laneId);

    /** The lanes that are free right now. */
    Set<String> freeLaneIds();

    /** Whether a lane is free right now, so a worker claims a case only when it can play it. */
    boolean hasFreeLane();
}
