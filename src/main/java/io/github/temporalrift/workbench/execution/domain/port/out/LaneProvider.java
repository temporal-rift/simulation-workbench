package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/**
 * Hands out isolated service lanes, at most one case per lane at a time. A lane is handed out for any case whose
 * {@link LaneFit} it satisfies, whatever rules the case plays under. A lane that hosted a case is offered again only
 * once the case's execution context is closed on its services.
 */
public interface LaneProvider {

    /**
     * Acquires a free lane that fits {@code fit}, preferring {@code preferredLaneId} (the lane a recovering attempt
     * last played on), or returns empty when there is none.
     */
    Optional<CaseLane> acquire(LaneFit fit, String preferredLaneId);

    /**
     * Acquires exactly this lane, or returns empty when it is not free, not configured, or does not fit. A
     * reproduction needs the lane that played the case: its bot identities are part of what the seats observe.
     */
    Optional<CaseLane> acquireExactly(String laneId, LaneFit fit);

    /**
     * The bundles some configured lane serves, whatever its availability: one per distinct digests, with its
     * provenance and lane count, ordered by rules digest and then content digest.
     */
    List<ServedBundle> servedBundles();

    /** The lanes that are free right now, each with the fit it satisfies. */
    Map<String, LaneFit> freeLanes();

    /**
     * Makes the lanes ready on start: the context a previous process left open on a lane is closed, and every lane is
     * offered only once prepared.
     */
    void prepare();
}
