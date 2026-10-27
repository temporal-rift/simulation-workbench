package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.execution;

import java.util.Optional;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;

/** Reads the simulator and the real lanes through the execution module's lane environments. */
public class FidelityEnvironmentsAdapter implements FidelityEnvironments {

    private final LaneEnvironments lanes;

    public FidelityEnvironmentsAdapter(LaneEnvironments lanes) {
        this.lanes = lanes;
    }

    @Override
    public Optional<Simulator> simulator() {
        return lanes.simulator()
                .map(bundle -> new Simulator(
                        environment(bundle),
                        bundle.rulesArtifact().digest(),
                        bundle.rulesArtifact().name(),
                        bundle.contentArtifact().digest(),
                        bundle.contentArtifact().name(),
                        bundle.effectiveRulesDigest(),
                        bundle.laneCount()));
    }

    @Override
    public Optional<RealLane> equivalentLane() {
        return lanes.equivalentDeployedLane(FidelityMatrix.MAX_SEATS)
                .map(lane -> new RealLane(lane.laneId(), environment(lane.bundle())));
    }

    private static FidelityEnvironment environment(LaneBundle bundle) {
        return new FidelityEnvironment(
                build(bundle.gameService()),
                build(bundle.timelineService()),
                build(bundle.readService()),
                bundle.effectiveContentDigest(),
                bundle.contracts());
    }

    private static FidelityEnvironment.ServiceBuild build(LaneBundle.ServiceBuild build) {
        return new FidelityEnvironment.ServiceBuild(build.imageDigest(), build.sourceRevision());
    }
}
