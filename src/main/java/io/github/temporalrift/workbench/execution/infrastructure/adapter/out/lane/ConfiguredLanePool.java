package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;

/**
 * The lanes this deployment was configured with; each hosts one case at a time of the bundle it serves, and a lane
 * with a reset hook is reset through it between cases.
 */
public class ConfiguredLanePool extends RosterBackedLaneProvider {

    private final Map<String, LaneEndpoints> lanes;
    private final ApiClients clients;
    private final LaneServices services;
    private final Clock clock;
    private final HttpGameSession.Pacing pacing;

    /** @param roster the availability of exactly these lanes, each as its {@link LaneEndpoints#member() member} */
    public ConfiguredLanePool(
            List<LaneEndpoints> lanes,
            LaneRoster roster,
            ApiClients clients,
            LaneServices services,
            Clock clock,
            HttpGameSession.Pacing pacing) {
        super(
                roster,
                ServedBundle.counted(lanes.stream()
                        .map(lane -> ServedBundle.ofLane(lane.bundle(), lane.provenance()))
                        .toList()));
        this.lanes = lanes.stream().collect(Collectors.toUnmodifiableMap(LaneEndpoints::id, Function.identity()));
        this.clients = clients;
        this.services = services;
        this.clock = clock;
        this.pacing = pacing;
    }

    @Override
    protected CaseLane lane(LaneRoster.Tenancy tenancy) {
        return new HttpCaseLane(lanes.get(tenancy.laneId()), clients, services, clock, pacing, tenancy);
    }
}
