package io.github.temporalrift.workbench.execution.domain.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class BuildEquivalenceTest {

    private static final BundleDigests DIGESTS = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final List<UUID> BOTS = List.of(
            UUID.fromString("00000000-0000-4000-8000-000000000001"),
            UUID.fromString("00000000-0000-4000-8000-000000000002"),
            UUID.fromString("00000000-0000-4000-8000-000000000003"));

    private static LaneProfile profile(
            LaneProfile.Kind kind,
            String imageDigest,
            String gameRevision,
            Map<String, String> contracts,
            String contentDigest,
            List<UUID> bots) {
        var game = new BundleProvenance.ServiceBuild(imageDigest, gameRevision);
        var other = new BundleProvenance.ServiceBuild(imageDigest, "2222222");
        var provenance = new BundleProvenance(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                game,
                other,
                other,
                new TreeMap<>(contracts));
        var digests = new BundleDigests(DIGESTS.rulesDigest(), contentDigest);
        return new LaneProfile(LaneFit.of(contentDigest, provenance), digests, provenance, kind, bots);
    }

    private static LaneProfile simulator() {
        return profile(
                LaneProfile.Kind.IN_PROCESS,
                "sha256:" + "1".repeat(64),
                "1111111",
                Map.of("session-api", "3.0.0"),
                DIGESTS.contentDigest(),
                BOTS);
    }

    @Test
    void aLaneOfTheSameSourceIsEquivalentWhateverItsImageDigest() {
        var lane = profile(
                LaneProfile.Kind.DEPLOYED,
                "sha256:" + "9".repeat(64),
                "1111111",
                Map.of("session-api", "3.0.0"),
                DIGESTS.contentDigest(),
                BOTS);

        assertThat(lane.fit()).isNotEqualTo(simulator().fit());
        assertThat(BuildEquivalence.holds(simulator(), lane, 3)).isTrue();
    }

    @Test
    void aDifferentSourceRevisionContractVersionOrContentIsNotEquivalent() {
        var image = "sha256:" + "9".repeat(64);
        var revision = profile(
                LaneProfile.Kind.DEPLOYED,
                image,
                "1111112",
                Map.of("session-api", "3.0.0"),
                DIGESTS.contentDigest(),
                BOTS);
        var contract = profile(
                LaneProfile.Kind.DEPLOYED,
                image,
                "1111111",
                Map.of("session-api", "3.0.1"),
                DIGESTS.contentDigest(),
                BOTS);
        var content = profile(
                LaneProfile.Kind.DEPLOYED, image, "1111111", Map.of("session-api", "3.0.0"), "e".repeat(64), BOTS);

        assertThat(BuildEquivalence.firstDifference(simulator(), revision, 3)).contains("game-service source revision");
        assertThat(BuildEquivalence.firstDifference(simulator(), contract, 3)).contains("contract versions");
        assertThat(BuildEquivalence.firstDifference(simulator(), content, 3)).contains("content digest");
    }

    @Test
    void aDifferentBotPlayerIdInAnyUsedSeatIsNotEquivalent() {
        var swapped = List.of(BOTS.get(0), BOTS.get(1), UUID.fromString("00000000-0000-4000-8000-0000000000ff"));
        var lane = profile(
                LaneProfile.Kind.DEPLOYED,
                "sha256:" + "9".repeat(64),
                "1111111",
                Map.of("session-api", "3.0.0"),
                DIGESTS.contentDigest(),
                swapped);

        assertThat(BuildEquivalence.firstDifference(simulator(), lane, 3)).contains("bot player id of seat 2");
        assertThat(BuildEquivalence.holds(simulator(), lane, 2)).isTrue();
    }

    @Test
    void aLaneWithFewerBotsThanTheCaseNeedsIsNotEquivalent() {
        var lane = profile(
                LaneProfile.Kind.DEPLOYED,
                "sha256:" + "9".repeat(64),
                "1111111",
                Map.of("session-api", "3.0.0"),
                DIGESTS.contentDigest(),
                BOTS.subList(0, 2));

        assertThat(BuildEquivalence.firstDifference(simulator(), lane, 3)).contains("bot identities for 3 seats");
    }
}
