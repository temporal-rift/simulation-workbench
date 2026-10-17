package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/** Lanes whose availability a {@link LaneRoster} tracks; only the lane handed out for a tenancy differs. */
public abstract class RosterBackedLaneProvider implements LaneProvider {

    private final LaneRoster roster;
    private final List<ServedBundle> servedBundles;

    protected RosterBackedLaneProvider(LaneRoster roster, List<ServedBundle> servedBundles) {
        this.roster = roster;
        this.servedBundles = List.copyOf(servedBundles);
    }

    /** The lane that serves one acquisition of a lane of the roster. */
    protected abstract CaseLane lane(LaneRoster.Tenancy tenancy);

    @Override
    public Optional<CaseLane> acquire(UUID caseId, BundleDigests bundle, String preferredLaneId) {
        return roster.take(caseId, bundle, preferredLaneId).map(this::lane);
    }

    @Override
    public Optional<CaseLane> acquireExactly(String laneId, BundleDigests bundle) {
        return roster.takeExactly(laneId, bundle).map(this::lane);
    }

    @Override
    public List<ServedBundle> servedBundles() {
        return servedBundles;
    }

    @Override
    public Map<String, BundleDigests> freeLanes() {
        return roster.free();
    }

    @Override
    public Set<UUID> heldCases() {
        return roster.heldCases();
    }

    @Override
    public void prepare(Collection<Hold> interrupted) {
        roster.prepare(interrupted);
    }

    @Override
    public void releaseHeld(UUID caseId) {
        roster.releaseHeld(caseId);
    }
}
