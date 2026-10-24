package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.shared.game.Faction;

class MatrixPreviewServiceTest {

    private static final String DIGEST = "a".repeat(64);

    private static tools.jackson.databind.JsonNode withSeeds(
            tools.jackson.databind.JsonNode manifest, String... seeds) {
        var copy = (tools.jackson.databind.node.ObjectNode) manifest.deepCopy();
        var values = copy.putArray("seeds");
        java.util.Arrays.stream(seeds).forEach(values::add);
        return copy;
    }

    @Test
    void singlePolicySingleVariantEnumerates55BaseCases() {
        var manifest = ExperimentManifests.singleSeedSinglePolicySingleVariant();

        var cases = MatrixPreviewService.preview(manifest, "a".repeat(64));

        assertThat(cases).hasSize(55);
        assertThat(cases.stream()
                        .map(MatrixPreviewService.CaseCoordinate::caseKey)
                        .distinct())
                .hasSize(55);
    }

    @Test
    void aPageIsTheMatchingWindowOfThePreview() {
        var manifest = ExperimentManifests.valid();
        var view = ExperimentValidator.validate(manifest);
        var all = MatrixPreviewService.preview(manifest, DIGEST);

        assertThat(MatrixPreviewService.page(view, DIGEST, 0, 220)).isEqualTo(all);
        assertThat(MatrixPreviewService.page(view, DIGEST, 57, 23)).isEqualTo(all.subList(57, 80));
        assertThat(MatrixPreviewService.page(view, DIGEST, 200, 100)).isEqualTo(all.subList(200, 220));
        assertThat(MatrixPreviewService.page(view, DIGEST, 220, 10)).isEmpty();
    }

    @Test
    void aNegativeOffsetStartsAtTheFirstCoordinate() {
        var view = ExperimentValidator.validate(ExperimentManifests.valid());

        assertThat(MatrixPreviewService.page(view, DIGEST, -1000, 3))
                .isEqualTo(MatrixPreviewService.page(view, DIGEST, 0, 3));
    }

    @Test
    void theFrozenOrderPutsSeedsThenVariantsThenPoliciesThenFactionSets() {
        var view = ExperimentValidator.validate(ExperimentManifests.valid());

        var first = MatrixPreviewService.page(view, DIGEST, 0, 1).getFirst();
        var secondPolicy = MatrixPreviewService.page(view, DIGEST, 55, 1).getFirst();
        var secondVariant = MatrixPreviewService.page(view, DIGEST, 110, 1).getFirst();
        var last = MatrixPreviewService.page(view, DIGEST, 219, 1).getFirst();

        assertThat(first.variantLabel()).isEqualTo("baseline");
        assertThat(first.playerCount()).isEqualTo(3);
        assertThat(first.rotation()).isZero();
        assertThat(first.seats().getFirst().policyId())
                .isEqualTo(view.policies().getFirst().id());
        assertThat(first.caseKey())
                .isEqualTo(java.util.UUID.nameUUIDFromBytes((DIGEST + "|42|baseline|"
                                + view.policies().getFirst().id() + "|"
                                + view.policies().getFirst().version() + "|3|"
                                + String.join(",", first.factionSet()) + "|0")
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(secondPolicy.variantLabel()).isEqualTo("baseline");
        assertThat(secondPolicy.seats().getFirst().policyId())
                .isEqualTo(view.policies().get(1).id());
        assertThat(secondPolicy.factionSet()).isEqualTo(first.factionSet());
        assertThat(secondVariant.variantLabel()).isEqualTo("candidate");
        assertThat(secondVariant.seats().getFirst().policyId())
                .isEqualTo(view.policies().getFirst().id());
        assertThat(last.variantLabel()).isEqualTo("candidate");
        assertThat(last.playerCount()).isEqualTo(5);
        assertThat(last.rotation()).isEqualTo(4);
    }

    @Test
    void aPageOfALargeMatrixVisitsOnlyItsWindow() {
        var manifest = ExperimentManifests.fourPlayerSeeds(5000, 4);
        var view = ExperimentValidator.validate(manifest);
        var visited = new java.util.ArrayList<MatrixPreviewService.CaseCoordinate>();

        MatrixPreviewService.page(view, DIGEST, 50_000, 20, visited::add);

        assertThat(MatrixPreviewService.size(view).total()).isEqualTo(100_000);
        assertThat(visited).hasSize(20);
        assertThat(visited.getFirst())
                .isEqualTo(MatrixPreviewService.preview(manifest, DIGEST).get(50_000));
    }

    @Test
    void theSizeIsTheNumberOfCoordinatesAndTheirSplitByVariantAndPlayerCount() {
        for (var candidate : List.of(
                ExperimentManifests.valid(),
                ExperimentManifests.singleSeedSinglePolicySingleVariant(),
                ExperimentManifests.fourPlayerSeeds(3, 2),
                withSeeds(ExperimentManifests.valid(), "100", "7", "42"))) {
            var size = MatrixPreviewService.size(candidate);
            var cases = MatrixPreviewService.preview(candidate, DIGEST);

            assertThat(size.total()).isEqualTo(cases.size());
            var counted = new java.util.LinkedHashMap<String, java.util.TreeMap<Integer, Integer>>();
            cases.forEach(c -> counted.computeIfAbsent(c.variantLabel(), v -> new java.util.TreeMap<>())
                    .merge(c.playerCount(), 1, Integer::sum));
            var expected = new java.util.ArrayList<MatrixPreviewService.Slice>();
            counted.forEach((variant, byPlayers) -> byPlayers.forEach(
                    (players, count) -> expected.add(new MatrixPreviewService.Slice(variant, players, count))));
            assertThat(size.breakdown()).isEqualTo(expected);
        }
    }

    @Test
    void twoPoliciesTwoVariantsEnumerate220Cases() {
        var manifest = ExperimentManifests.valid();

        var cases = MatrixPreviewService.preview(manifest, "a".repeat(64));

        assertThat(cases).hasSize(220);
        assertThat(cases.stream()
                        .map(MatrixPreviewService.CaseCoordinate::caseKey)
                        .distinct())
                .hasSize(220);
    }

    @Test
    void threePlayerSetsContribute30CasesFourPlayer20AndFivePlayer5() {
        var manifest = ExperimentManifests.singleSeedSinglePolicySingleVariant();

        var cases = MatrixPreviewService.preview(manifest, "a".repeat(64));
        var byCount = cases.stream()
                .collect(java.util.stream.Collectors.groupingBy(MatrixPreviewService.CaseCoordinate::playerCount));

        assertThat(byCount.get(3)).hasSize(30);
        assertThat(byCount.get(4)).hasSize(20);
        assertThat(byCount.get(5)).hasSize(5);
    }

    @Test
    void cyclicRotationPlacesEveryFactionInEverySeat() {
        var manifest = ExperimentManifests.singleSeedSinglePolicySingleVariant();

        var cases = MatrixPreviewService.preview(manifest, "a".repeat(64));
        var threePlayer = cases.stream().filter(c -> c.playerCount() == 3).toList();
        var factionsBySeat = threePlayer.stream()
                .flatMap(c -> c.seats().stream())
                .collect(java.util.stream.Collectors.groupingBy(
                        MatrixPreviewService.SeatAssignment::seatIndex,
                        java.util.stream.Collectors.mapping(
                                MatrixPreviewService.SeatAssignment::faction, java.util.stream.Collectors.toSet())));

        assertThat(factionsBySeat).hasSize(3);
        factionsBySeat
                .values()
                .forEach(factions -> assertThat(factions)
                        .extracting(Faction::name)
                        .containsExactlyInAnyOrderElementsOf(ExperimentManifests.FACTIONS));
    }

    @Test
    void enumerationIsDeterministic() {
        var manifest = ExperimentManifests.valid();

        var first = MatrixPreviewService.preview(manifest, "a".repeat(64));
        var second = MatrixPreviewService.preview(manifest, "a".repeat(64));

        assertThat(second).isEqualTo(first);
    }
}
