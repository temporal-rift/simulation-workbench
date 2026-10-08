package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;

/** The lanes this deployment was configured with; each hosts one case at a time. */
public class ConfiguredLanePool implements LaneProvider {

    private final List<LaneEndpoints> lanes;
    private final ApiClients clients;
    private final CommandLedger ledger;
    private final EvidenceLedger evidence;
    private final GameEventObservers observers;
    private final Clock clock;
    private final LaneEndpoints.Barrier barrier;
    private final Sleeper sleeper;
    private final Set<String> busy = new HashSet<>();

    public ConfiguredLanePool(
            List<LaneEndpoints> lanes,
            ApiClients clients,
            CommandLedger ledger,
            EvidenceLedger evidence,
            GameEventObservers observers,
            Clock clock,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper) {
        this.lanes = List.copyOf(lanes);
        this.clients = clients;
        this.ledger = ledger;
        this.evidence = evidence;
        this.observers = observers;
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
        chosen.ifPresent(lane -> busy.add(lane.id()));
        return chosen.map(lane -> new HttpCaseLane(
                lane, clients, ledger, evidence, observers, clock, barrier, sleeper, () -> release(lane.id())));
    }

    @Override
    public synchronized boolean hasFreeLane() {
        return lanes.stream().anyMatch(lane -> !busy.contains(lane.id()));
    }

    private synchronized void release(String laneId) {
        busy.remove(laneId);
    }
}
