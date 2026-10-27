package io.github.temporalrift.workbench.execution.application.query;

import java.util.Comparator;
import java.util.Map;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BuildEquivalence;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.LaneProfile;
import io.github.temporalrift.workbench.experiment.LaneBundle;

/** Describes the simulator and the deployed lanes, and which deployed lane plays the simulator's game. */
public class LaneEnvironmentsQueryHandler implements LaneEnvironments {

    private final LaneProvider lanes;

    public LaneEnvironmentsQueryHandler(LaneProvider lanes) {
        this.lanes = lanes;
    }

    @Override
    public Optional<LaneBundle> simulator() {
        return simulatorProfile().map(LaneEnvironmentsQueryHandler::bundle);
    }

    @Override
    public Optional<DeployedLane> equivalentDeployedLane(int seats) {
        return simulatorProfile()
                .flatMap(simulator -> lanes.profiles().entrySet().stream()
                        .filter(lane -> lane.getValue().kind() == LaneProfile.Kind.DEPLOYED)
                        .filter(lane -> BuildEquivalence.holds(simulator, lane.getValue(), seats))
                        .min(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                        .map(lane -> new DeployedLane(lane.getKey(), bundle(lane.getValue()))));
    }

    private Optional<LaneProfile> simulatorProfile() {
        return lanes.profiles().entrySet().stream()
                .filter(lane -> lane.getValue().kind() == LaneProfile.Kind.IN_PROCESS)
                .min(Map.Entry.comparingByKey(Comparator.naturalOrder()))
                .map(Map.Entry::getValue);
    }

    private static LaneBundle bundle(LaneProfile profile) {
        var provenance = profile.provenance();
        return new LaneBundle(
                artifact(provenance.rulesArtifact()),
                artifact(provenance.contentArtifact()),
                profile.bundle().rulesDigest(),
                profile.bundle().contentDigest(),
                build(provenance.gameService()),
                build(provenance.timelineService()),
                build(provenance.readService()),
                provenance.contracts(),
                1);
    }

    private static LaneBundle.Artifact artifact(BundleProvenance.Artifact artifact) {
        return new LaneBundle.Artifact(artifact.digest(), artifact.name());
    }

    private static LaneBundle.ServiceBuild build(BundleProvenance.ServiceBuild build) {
        return new LaneBundle.ServiceBuild(build.imageDigest(), build.sourceRevision());
    }
}
