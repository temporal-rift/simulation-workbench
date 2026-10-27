package io.github.temporalrift.workbench.fidelity.domain.port.out;

import java.util.Optional;

import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;

/** What the simulator and the real lanes are. */
public interface FidelityEnvironments {

    /** The simulator, or empty when it is not enabled. */
    Optional<Simulator> simulator();

    /** The real lane that is build-equivalent to the simulator for every seat a case may use, if any. */
    Optional<RealLane> equivalentLane();

    /**
     * The simulator's environment with the artifacts of the bundle it serves, which a manifest names.
     *
     * @param rulesArtifactDigest the baseline rules document, named by its digest; {@code rulesArtifactName} its name
     * @param laneCount how many cases the simulator plays at once
     */
    record Simulator(
            FidelityEnvironment environment,
            String rulesArtifactDigest,
            String rulesArtifactName,
            String contentArtifactDigest,
            String contentArtifactName,
            String effectiveRulesDigest,
            int laneCount) {}

    record RealLane(String laneId, FidelityEnvironment environment) {}
}
