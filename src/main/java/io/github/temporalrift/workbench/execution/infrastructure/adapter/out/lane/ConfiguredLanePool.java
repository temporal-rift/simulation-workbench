package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * The lanes this deployment was configured with; each hosts one case at a time of the bundle it serves, and a lane
 * with a reset hook is reset through it between cases.
 */
public class ConfiguredLanePool implements LaneProvider {

    private final Map<String, LaneEndpoints> lanes;
    private final LaneRoster roster;
    private final ApiClients clients;
    private final LaneServices services;
    private final Clock clock;
    private final LaneEndpoints.Barrier barrier;
    private final Sleeper sleeper;

    public ConfiguredLanePool(
            List<LaneEndpoints> lanes,
            ApiClients clients,
            LaneServices services,
            Clock clock,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper,
            LaneResets resets,
            ResetScheduler scheduler) {
        this.lanes = lanes.stream().collect(Collectors.toUnmodifiableMap(LaneEndpoints::id, Function.identity()));
        this.roster = new LaneRoster(
                lanes.stream()
                        .map(lane -> new LaneRoster.Member(lane.id(), lane.bundle(), lane.resetUrl() != null))
                        .toList(),
                resets,
                scheduler);
        this.clients = clients;
        this.services = services;
        this.clock = clock;
        this.barrier = barrier;
        this.sleeper = sleeper;
    }

    @Override
    public Optional<CaseLane> acquire(UUID caseId, BundleDigests bundle, String preferredLaneId) {
        return roster.take(caseId, bundle, preferredLaneId).map(this::lane);
    }

    @Override
    public Optional<CaseLane> acquireExactly(String laneId, BundleDigests bundle) {
        return roster.takeExactly(laneId, bundle).map(this::lane);
    }

    @Override
    public Set<BundleDigests> servedBundles() {
        return roster.servedBundles();
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

    private CaseLane lane(LaneRoster.Tenancy tenancy) {
        return new HttpCaseLane(lanes.get(tenancy.laneId()), clients, services, clock, barrier, sleeper, tenancy);
    }
}
