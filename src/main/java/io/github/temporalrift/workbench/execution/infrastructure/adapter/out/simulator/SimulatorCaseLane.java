package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.HttpCaseLane;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.HttpGameSession;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneRoster;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneServices;

/**
 * A slot acquired for one case. Opening starts the slot's services and then plays the case exactly as on a real lane:
 * the bots call the services' REST operations on their loopback ports, and the game is driven and settled through the
 * services' execution controls. Only what a broker would carry differs: the observer reads the slot's router.
 */
final class SimulatorCaseLane implements CaseLane {

    private final SimulatorSlot slot;
    private final LaneRoster.Tenancy tenancy;
    private final SimulatorLanePool.Hosting hosting;
    private HttpCaseLane hosted;

    SimulatorCaseLane(SimulatorSlot slot, LaneRoster.Tenancy tenancy, SimulatorLanePool.Hosting hosting) {
        this.slot = slot;
        this.tenancy = tenancy;
        this.hosting = hosting;
    }

    @Override
    public String laneId() {
        return slot.id();
    }

    @Override
    public GameSession open(CaseContext context) {
        // From here the slot hosts the case, so returning it must close whatever context reached its services.
        tenancy.host(context);
        var runtime = slot.runtime();
        var endpoints = SimulatorLanePool.endpoints(slot, runtime, hosting.bundle(), hosting.provenance());
        var services = new LaneServices(
                hosting.commands(),
                hosting.evidence(),
                (_, scopeId, attemptId, gameId, _) -> new SimulatorEventObserver(
                        runtime.router(),
                        hosting.evidence(),
                        hosting.objectMapper(),
                        new SimulatorEventObserver.Target(scopeId, attemptId, gameId)));
        hosted = new HttpCaseLane(
                endpoints,
                hosting.clients(),
                services,
                hosting.clock(),
                new HttpGameSession.Pacing(hosting.barrier(), hosting.sleeper(), hosting.waits()),
                tenancy);
        return hosted.open(context);
    }

    @Override
    public void close() {
        if (hosted != null) {
            hosted.close();
        } else {
            // No context reached the services, so they are as clean as before the case.
            tenancy.giveBack();
            slot.released();
        }
    }
}
