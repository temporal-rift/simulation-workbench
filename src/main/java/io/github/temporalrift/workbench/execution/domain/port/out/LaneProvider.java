package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/**
 * Hands out isolated service lanes, at most one case per lane at a time. Each lane serves one frozen bundle, and a
 * lane is handed out only for a case of that bundle. A lane that hosted a case is made clean before another case gets
 * it, unless the case's attempt was interrupted: then the lane keeps the game for that case alone.
 */
public interface LaneProvider {

    /**
     * Acquires a free lane serving {@code bundle}, or a lane holding the game of {@code caseId}, preferring
     * {@code preferredLaneId} (the lane a recovering attempt last played on), or returns empty when there is none.
     */
    Optional<CaseLane> acquire(UUID caseId, BundleDigests bundle, String preferredLaneId);

    /**
     * Acquires exactly this lane, or returns empty when it is not free, not configured, or serves another bundle. A
     * reproduction needs the lane that played the case: its bot identities are part of what the seats observe.
     */
    Optional<CaseLane> acquireExactly(String laneId, BundleDigests bundle);

    /**
     * The bundles some configured lane serves, whatever its availability: one per distinct digests, with its
     * provenance and lane count, ordered by rules digest and then content digest.
     */
    List<ServedBundle> servedBundles();

    /** The lanes that are free right now, each with the bundle it serves. */
    Map<String, BundleDigests> freeLanes();

    /** The cases an interrupted attempt left a lane holding the game of. */
    Set<UUID> heldCases();

    /**
     * Makes the lanes ready on start: each lane in {@code interrupted} keeps the game of its case, every other lane is
     * made clean before it is first offered.
     */
    void prepare(Collection<Hold> interrupted);

    /** Makes the lanes held for the case clean, as the case no longer needs its game. */
    void releaseHeld(UUID caseId);

    /** A lane that keeps the game of an interrupted attempt of a case. */
    record Hold(String laneId, UUID caseId, UUID caseKey) {}
}
