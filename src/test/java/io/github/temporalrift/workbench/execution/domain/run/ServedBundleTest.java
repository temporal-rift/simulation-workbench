package io.github.temporalrift.workbench.execution.domain.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ServedBundleTest {

    private static final BundleDigests DIGESTS = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final Map<String, String> CONTRACTS = Map.of("session-api", "3.0.0");

    private static ServedBundle served(String imageDigest) {
        var build = new BundleProvenance.ServiceBuild(imageDigest, "abc1234");
        var provenance = new BundleProvenance(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                build,
                build,
                build,
                new java.util.TreeMap<>(CONTRACTS));
        return ServedBundle.ofLane(DIGESTS, provenance);
    }

    @Test
    void lanesOfOneBuildServingOneBundleAreCountedTogether() {
        var counted =
                ServedBundle.counted(List.of(served("sha256:" + "1".repeat(64)), served("sha256:" + "1".repeat(64))));

        assertThat(counted)
                .singleElement()
                .satisfies(bundle -> assertThat(bundle.laneCount()).isEqualTo(2));
    }

    @Test
    void theSameDigestsFromOtherBuildsAreSeparateBundles() {
        var simulator = served("sha256:" + "1".repeat(64));
        var realLane = served("sha256:" + "2".repeat(64));

        var counted = ServedBundle.counted(List.of(realLane, simulator));

        assertThat(counted)
                .hasSize(2)
                .allSatisfy(bundle -> assertThat(bundle.laneCount()).isEqualTo(1));
        assertThat(counted).extracting(bundle -> bundle.fit().digest()).isSorted();
        assertThat(counted).extracting(ServedBundle::fit).containsExactlyInAnyOrder(simulator.fit(), realLane.fit());
    }
}
