package io.github.temporalrift.workbench.execution;

import java.util.Optional;

import io.github.temporalrift.workbench.experiment.LaneBundle;

/** What the simulator and the deployed lanes are, for the module that checks one against the other. */
public interface LaneEnvironments {

    /** The bundle and builds the simulator serves, or empty when the simulator is not enabled. */
    Optional<LaneBundle> simulator();

    /**
     * The deployed lane with the lowest id that is build-equivalent to the simulator for cases of up to {@code seats}
     * seats, or empty when the simulator is not enabled or no lane is.
     */
    Optional<DeployedLane> equivalentDeployedLane(int seats);

    /** A deployed lane and what it serves. */
    record DeployedLane(String laneId, LaneBundle bundle) {}
}
