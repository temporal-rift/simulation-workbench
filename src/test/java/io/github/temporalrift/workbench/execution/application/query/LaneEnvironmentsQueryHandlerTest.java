package io.github.temporalrift.workbench.execution.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LaneProfile;

class LaneEnvironmentsQueryHandlerTest {

    private static final BundleDigests DIGESTS = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final List<UUID> BOTS =
            List.of(new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), new UUID(0, 4), new UUID(0, 5));

    private final LaneProvider lanes = mock(LaneProvider.class);
    private final LaneEnvironmentsQueryHandler environments = new LaneEnvironmentsQueryHandler(lanes);

    private static LaneProfile profile(LaneProfile.Kind kind, String image, String revision, List<UUID> bots) {
        var game = new BundleProvenance.ServiceBuild(image, revision);
        var provenance = new BundleProvenance(
                new BundleProvenance.Artifact("c".repeat(64), "baseline"),
                new BundleProvenance.Artifact("d".repeat(64), null),
                game,
                game,
                game,
                new TreeMap<>(Map.of("session-api", "3.0.0")));
        return new LaneProfile(LaneFit.of(DIGESTS.contentDigest(), provenance), DIGESTS, provenance, kind, bots);
    }

    private static LaneProfile simulator() {
        return profile(LaneProfile.Kind.IN_PROCESS, "sha256:" + "1".repeat(64), "1111111", BOTS);
    }

    private static LaneProfile lane(String revision) {
        return profile(LaneProfile.Kind.DEPLOYED, "sha256:" + "9".repeat(64), revision, BOTS);
    }

    @Test
    void withoutASimulatorThereIsNeitherABundleNorAnEquivalentLane() {
        when(lanes.profiles()).thenReturn(Map.of("lane-1", lane("1111111")));

        assertThat(environments.simulator()).isEmpty();
        assertThat(environments.equivalentDeployedLane(5)).isEmpty();
    }

    @Test
    void theSimulatorIsDescribedWithItsBuildsAndArtifacts() {
        when(lanes.profiles()).thenReturn(Map.of("simulator-1", simulator(), "lane-1", lane("1111111")));

        var bundle = environments.simulator().orElseThrow();

        assertThat(bundle.gameService().sourceRevision()).isEqualTo("1111111");
        assertThat(bundle.gameService().imageDigest()).isEqualTo("sha256:" + "1".repeat(64));
        assertThat(bundle.rulesArtifact().name()).isEqualTo("baseline");
        assertThat(bundle.effectiveContentDigest()).isEqualTo(DIGESTS.contentDigest());
        assertThat(bundle.contracts()).containsEntry("session-api", "3.0.0");
    }

    @Test
    void theDeployedLaneWithTheLowestIdThatPlaysTheSameGameIsChosen() {
        when(lanes.profiles())
                .thenReturn(Map.of(
                        "simulator-1", simulator(),
                        "lane-3", lane("1111111"),
                        "lane-2", lane("1111111"),
                        "lane-1", lane("2222222")));

        var chosen = environments.equivalentDeployedLane(5).orElseThrow();

        assertThat(chosen.laneId()).isEqualTo("lane-2");
        assertThat(chosen.bundle().gameService().imageDigest()).isEqualTo("sha256:" + "9".repeat(64));
    }

    @Test
    void aLaneWithAnotherBotPlayerIdInAnyUsedSeatIsNotChosen() {
        var other = List.of(BOTS.get(0), BOTS.get(1), BOTS.get(2), BOTS.get(3), new UUID(0, 99));
        when(lanes.profiles())
                .thenReturn(Map.of(
                        "simulator-1", simulator(),
                        "lane-1", profile(LaneProfile.Kind.DEPLOYED, "sha256:" + "9".repeat(64), "1111111", other)));

        assertThat(environments.equivalentDeployedLane(5)).isEmpty();
        assertThat(environments.equivalentDeployedLane(4)).isPresent();
    }

    @Test
    void theSimulatorPlaysOneCasePerSlotAndALaneOne() {
        when(lanes.profiles())
                .thenReturn(Map.of(
                        "simulator-1", simulator(),
                        "simulator-2", simulator(),
                        "simulator-3", simulator(),
                        "lane-1", lane("1111111")));

        assertThat(environments.simulator().orElseThrow().laneCount()).isEqualTo(3);
        assertThat(environments.equivalentDeployedLane(5).orElseThrow().bundle().laneCount())
                .isEqualTo(1);
    }

    @Test
    void aLaneIsEquivalentOnlyWhileItIsConfiguredAndPlaysTheSimulatorsGame() {
        when(lanes.profiles())
                .thenReturn(Map.of(
                        "simulator-1", simulator(),
                        "lane-1", lane("1111111"),
                        "lane-2", lane("2222222")));

        assertThat(environments.isEquivalentDeployedLane("lane-1", 5)).isTrue();
        assertThat(environments.isEquivalentDeployedLane("lane-2", 5)).isFalse();
        assertThat(environments.isEquivalentDeployedLane("lane-9", 5)).isFalse();
        assertThat(environments.isEquivalentDeployedLane("simulator-1", 5)).isFalse();
    }

    @Test
    void anotherSimulatorSlotIsNeverTakenForARealLane() {
        when(lanes.profiles()).thenReturn(Map.of("simulator-1", simulator(), "simulator-2", simulator()));

        assertThat(environments.equivalentDeployedLane(5)).isEmpty();
    }
}
