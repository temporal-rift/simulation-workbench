package io.github.temporalrift.workbench.fidelity.support;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;

/** The simulator, the real lane and the variant rules the fidelity tests agree on. */
public final class FidelityFixtures {

    public static final String SIMULATOR_IMAGE = "sha256:" + "1".repeat(64);
    public static final String LANE_IMAGE = "sha256:" + "9".repeat(64);

    private FidelityFixtures() {}

    public static FidelityEnvironment environment(String image, String revision) {
        var build = new FidelityEnvironment.ServiceBuild(image, revision);
        return new FidelityEnvironment(
                build, build, build, "b".repeat(64), new TreeMap<>(Map.of("session-api", "3.0.0")));
    }

    public static FidelityEnvironments.Simulator simulator(String revision) {
        return new FidelityEnvironments.Simulator(
                environment(SIMULATOR_IMAGE, revision), "c".repeat(64), null, "d".repeat(64), null, "a".repeat(64), 4);
    }

    public static FidelityEnvironments.RealLane lane(String revision) {
        return new FidelityEnvironments.RealLane("lane-1", environment(LANE_IMAGE, revision));
    }

    /** Environments with a simulator and an equivalent lane of the given source revision. */
    public static FidelityEnvironments environments(String revision) {
        return new FidelityEnvironments() {
            @Override
            public Optional<Simulator> simulator() {
                return Optional.of(FidelityFixtures.simulator(revision));
            }

            @Override
            public Optional<RealLane> equivalentLane() {
                return Optional.of(lane(revision));
            }
        };
    }

    /** The rules digest a variant means is a function of its overrides, which is all the tests need of it. */
    public static FidelityExperiments.VariantRules rules(Map<String, BigDecimal> overrides) {
        return new FidelityExperiments.VariantRules(
                String.format("%064x", Math.abs(overrides.hashCode())), new TreeMap<>(overrides));
    }
}
