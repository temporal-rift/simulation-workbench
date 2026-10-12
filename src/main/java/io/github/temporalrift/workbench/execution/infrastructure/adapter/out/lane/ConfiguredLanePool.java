package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/** The lanes this deployment was configured with; each hosts one case at a time of the bundle it serves. */
public class ConfiguredLanePool implements LaneProvider {

    private final List<LaneEndpoints> lanes;
    private final ApiClients clients;
    private final LaneServices services;
    private final Clock clock;
    private final LaneEndpoints.Barrier barrier;
    private final Sleeper sleeper;
    private final Set<String> busy = new HashSet<>();

    public ConfiguredLanePool(
            List<LaneEndpoints> lanes,
            ApiClients clients,
            LaneServices services,
            Clock clock,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper) {
        this.lanes = List.copyOf(lanes);
        this.clients = clients;
        this.services = services;
        this.clock = clock;
        this.barrier = barrier;
        this.sleeper = sleeper;
    }

    @Override
    public synchronized Optional<CaseLane> acquire(BundleDigests bundle, String preferredLaneId) {
        var serving = lanes.stream()
                .filter(lane -> !busy.contains(lane.id()))
                .filter(lane -> lane.bundle().equals(bundle))
                .toList();
        return serving.stream()
                .filter(lane -> lane.id().equals(preferredLaneId))
                .findFirst()
                .or(() -> serving.stream().findFirst())
                .map(this::take);
    }

    @Override
    public synchronized Optional<CaseLane> acquireExactly(String laneId, BundleDigests bundle) {
        return lanes.stream()
                .filter(lane -> !busy.contains(lane.id()))
                .filter(lane -> lane.id().equals(laneId))
                .filter(lane -> lane.bundle().equals(bundle))
                .findFirst()
                .map(this::take);
    }

    @Override
    public Set<BundleDigests> servedBundles() {
        return lanes.stream().map(LaneEndpoints::bundle).collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public synchronized Map<String, BundleDigests> freeLanes() {
        return lanes.stream()
                .filter(lane -> !busy.contains(lane.id()))
                .collect(Collectors.toUnmodifiableMap(LaneEndpoints::id, LaneEndpoints::bundle));
    }

    private CaseLane take(LaneEndpoints lane) {
        busy.add(lane.id());
        return new HttpCaseLane(lane, clients, services, clock, barrier, sleeper, () -> release(lane.id()));
    }

    private synchronized void release(String laneId) {
        busy.remove(laneId);
    }
}
