package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * Hands out isolated service lanes, at most one case per lane at a time. Each lane serves one frozen bundle, and a
 * lane is handed out only for a case of that bundle.
 */
public interface LaneProvider {

    /**
     * Acquires a free lane serving {@code bundle}, preferring {@code preferredLaneId} (the lane a recovering attempt
     * last played on), or returns empty when every such lane is busy.
     */
    Optional<CaseLane> acquire(BundleDigests bundle, String preferredLaneId);

    /**
     * Acquires exactly this lane, or returns empty when it is busy, not configured, or serves another bundle. A
     * reproduction needs the lane that played the case: its bot identities are part of what the seats observe.
     */
    Optional<CaseLane> acquireExactly(String laneId, BundleDigests bundle);

    /** The bundles some configured lane serves, busy or free. */
    Set<BundleDigests> servedBundles();

    /** The lanes that are free right now, each with the bundle it serves. */
    Map<String, BundleDigests> freeLanes();
}
