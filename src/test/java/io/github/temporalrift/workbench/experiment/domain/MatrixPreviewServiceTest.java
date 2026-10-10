package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;

class MatrixPreviewServiceTest {

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
    void enumeratingVisitsTheSameCoordinatesInTheSameOrderAsPreviewing() {
        var manifest = ExperimentManifests.valid();
        var visited = new java.util.ArrayList<MatrixPreviewService.CaseCoordinate>();

        MatrixPreviewService.enumerate(manifest, "a".repeat(64), visited::add);

        assertThat(visited).containsExactlyElementsOf(MatrixPreviewService.preview(manifest, "a".repeat(64)));
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
                .forEach(factions ->
                        assertThat(factions).containsExactlyInAnyOrderElementsOf(ExperimentManifests.FACTIONS));
    }

    @Test
    void enumerationIsDeterministic() {
        var manifest = ExperimentManifests.valid();

        var first = MatrixPreviewService.preview(manifest, "a".repeat(64));
        var second = MatrixPreviewService.preview(manifest, "a".repeat(64));

        assertThat(second).isEqualTo(first);
    }
}
