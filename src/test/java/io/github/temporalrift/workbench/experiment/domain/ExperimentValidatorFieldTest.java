package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;

/** A refusal names the path of the manifest value it is about, so a client can place it on that input. */
class ExperimentValidatorFieldTest {

    private static Arguments refusal(String field, ExperimentErrorCode code, Consumer<ObjectNode> corrupt) {
        return Arguments.of(field, code, corrupt);
    }

    static Stream<Arguments> refusals() {
        return Stream.of(
                refusal("schemaVersion", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.put("schemaVersion", 2)),
                refusal("schemaVersion", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("schemaVersion")),
                refusal("name", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.put("name", " ")),
                refusal("variants", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("variants")),
                refusal(
                        "variants[1].label",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("variants").get(1).asObject().put("label", "")),
                refusal(
                        "variants[1].rulesArtifact",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("variants").get(1).asObject().remove("rulesArtifact")),
                refusal(
                        "variants[0].contentArtifact.digest",
                        ExperimentErrorCode.MANIFEST_MISMATCH,
                        m -> m.withArray("variants")
                                .get(0)
                                .get("contentArtifact")
                                .asObject()
                                .put("digest", "short")),
                refusal("services", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("services")),
                refusal(
                        "services.readService",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.get("services").asObject().remove("readService")),
                refusal(
                        "services.gameService.imageDigest",
                        ExperimentErrorCode.MANIFEST_MISMATCH,
                        m -> m.get("services").get("gameService").asObject().put("imageDigest", "latest")),
                refusal(
                        "services.timelineService.sourceRevision",
                        ExperimentErrorCode.MANIFEST_MISMATCH,
                        m -> m.get("services").get("timelineService").asObject().put("sourceRevision", "HEAD")),
                refusal("contracts", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("contracts")),
                refusal(
                        "contracts.session-api",
                        ExperimentErrorCode.MANIFEST_MISMATCH,
                        m -> m.get("contracts").asObject().put("session-api", "latest")),
                refusal("policies", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("policies")),
                refusal(
                        "policies[0].id",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("policies").get(0).asObject().put("id", "")),
                refusal(
                        "policies[0].version",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("policies").get(0).asObject().put("version", "")),
                refusal(
                        "policies[1].artifactDigest",
                        ExperimentErrorCode.MANIFEST_MISMATCH,
                        m -> m.withArray("policies").get(1).asObject().put("artifactDigest", "x")),
                refusal(
                        "policies[0].parameters",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("policies").get(0).asObject().put("parameters", "none")),
                refusal(
                        "policies[0].seatAssignment",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("policies").get(0).asObject().remove("seatAssignment")),
                refusal(
                        "policies[0].seatAssignment[0].policyId",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> seat(m).put("policyId", "")),
                refusal(
                        "policies[0].seatAssignment[0].seatIndex",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> seat(m).put("seatIndex", -1)),
                refusal(
                        "policies[0].seatAssignment[1]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("policies")
                                .get(0)
                                .withArray("seatAssignment")
                                .add(seat(m).deepCopy())),
                refusal("seeds", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("seeds")),
                refusal(
                        "seeds[0]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("seeds").set(0, 42)),
                refusal(
                        "seeds[1]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("seeds").add("42")),
                refusal("playerCounts", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("playerCounts")),
                refusal(
                        "playerCounts[0]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("playerCounts").set(0, "three")),
                refusal(
                        "playerCounts[1]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("playerCounts").set(1, 3)),
                refusal("factionSets", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.remove("factionSets")),
                refusal(
                        "factionSets[0]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("factionSets").set(0, m.arrayNode().add("ERASERS"))),
                refusal(
                        "factionSets[0][1]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("factionSets").get(0).asArray().set(1, "NOBODY")),
                refusal(
                        "factionSets[1]",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("factionSets")
                                .set(1, m.withArray("factionSets").get(0))),
                refusal(
                        "seatRotationMode",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.put("seatRotationMode", "R")),
                refusal("timingMode", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.put("timingMode", "WALL")),
                refusal("concurrency", ExperimentErrorCode.INVALID_EXPERIMENT, m -> m.put("concurrency", 0)),
                refusal(
                        "caseWallTimeoutSeconds",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.remove("caseWallTimeoutSeconds")),
                refusal(
                        "maxRejectedCandidatesPerWindow",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.put("maxRejectedCandidatesPerWindow", "many")),
                refusal(
                        "variants[0].apiKey",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("variants").get(0).asObject().put("apiKey", "k")),
                refusal(
                        "variants[0].label",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.withArray("variants").get(0).asObject().put("label", "ghp_secret")),
                refusal(
                        "name",
                        ExperimentErrorCode.INVALID_EXPERIMENT,
                        m -> m.put("name", "C:\\Users\\someone\\experiment")));
    }

    private static ObjectNode seat(ObjectNode manifest) {
        return manifest.withArray("policies")
                .get(0)
                .withArray("seatAssignment")
                .get(0)
                .asObject();
    }

    @ParameterizedTest(name = "{0} ({1})")
    @MethodSource("refusals")
    void aRefusalNamesTheValueItIsAbout(String field, ExperimentErrorCode code, Consumer<ObjectNode> corrupt) {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        corrupt.accept(manifest);

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOfSatisfying(ExperimentValidationException.class, refusal -> {
                    assertThat(refusal.code()).isEqualTo(code);
                    assertThat(refusal.field()).contains(field);
                });
    }

    @org.junit.jupiter.api.Test
    void aMatrixOverTheBoundNamesNoSingleValue() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var seeds = manifest.withArray("seeds");
        for (var seed = 1; seed <= ExperimentValidator.MAX_CASES; seed++) {
            seeds.add(String.valueOf(seed + 1000));
        }

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOfSatisfying(ExperimentValidationException.class, refusal -> {
                    assertThat(refusal.code()).isEqualTo(ExperimentErrorCode.INVALID_EXPERIMENT);
                    assertThat(refusal.field()).isEmpty();
                });
    }
}
