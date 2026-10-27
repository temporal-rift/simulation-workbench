package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LaneProfile;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/** Lanes whose availability a {@link LaneRoster} tracks; only the lane handed out for a tenancy differs. */
public abstract class RosterBackedLaneProvider implements LaneProvider {

    private final LaneRoster roster;
    private final List<ServedBundle> servedBundles;
    private final Map<String, LaneProfile> profiles;

    protected RosterBackedLaneProvider(
            LaneRoster roster, List<ServedBundle> servedBundles, Map<String, LaneProfile> profiles) {
        this.roster = roster;
        this.servedBundles = List.copyOf(servedBundles);
        this.profiles = Map.copyOf(profiles);
    }

    /** The lane that serves one acquisition of a lane of the roster. */
    protected abstract CaseLane lane(LaneRoster.Tenancy tenancy);

    @Override
    public Optional<CaseLane> acquire(LaneFit fit, String preferredLaneId) {
        return roster.take(fit, preferredLaneId).map(this::lane);
    }

    @Override
    public Optional<CaseLane> acquireExactly(String laneId, LaneFit fit) {
        return roster.takeExactly(laneId, fit).map(this::lane);
    }

    @Override
    public List<ServedBundle> servedBundles() {
        return servedBundles;
    }

    @Override
    public Map<String, LaneProfile> profiles() {
        return profiles;
    }

    @Override
    public Map<String, LaneFit> freeLanes() {
        return roster.free();
    }

    @Override
    public void prepare() {
        roster.prepare();
    }
}
