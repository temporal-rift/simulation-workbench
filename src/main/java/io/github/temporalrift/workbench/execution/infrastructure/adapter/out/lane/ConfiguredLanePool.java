package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;

/** The lanes this deployment was configured with; each hosts one case at a time. */
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
    public synchronized Optional<CaseLane> acquire(String preferredLaneId) {
        var chosen = lanes.stream()
                .filter(lane -> !busy.contains(lane.id()))
                .filter(lane -> lane.id().equals(preferredLaneId))
                .findFirst()
                .or(() ->
                        lanes.stream().filter(lane -> !busy.contains(lane.id())).findFirst());
        return chosen.map(this::take);
    }

    @Override
    public synchronized Optional<CaseLane> acquireExactly(String laneId) {
        return lanes.stream()
                .filter(lane -> !busy.contains(lane.id()))
                .filter(lane -> lane.id().equals(laneId))
                .findFirst()
                .map(this::take);
    }

    @Override
    public synchronized boolean hasFreeLane() {
        return lanes.stream().anyMatch(lane -> !busy.contains(lane.id()));
    }

    @Override
    public synchronized Set<String> freeLaneIds() {
        return lanes.stream()
                .map(LaneEndpoints::id)
                .filter(id -> !busy.contains(id))
                .collect(Collectors.toSet());
    }

    private CaseLane take(LaneEndpoints lane) {
        busy.add(lane.id());
        return new HttpCaseLane(lane, clients, services, clock, barrier, sleeper, () -> release(lane.id()));
    }

    private synchronized void release(String laneId) {
        busy.remove(laneId);
    }
}
