package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;

class ExperimentValidatorTest {

    @Test
    void validManifestPasses() {
        var view = ExperimentValidator.validate(ExperimentManifests.valid());

        assertThat(view.name()).isEqualTo("threshold experiment");
        assertThat(view.variantLabels()).containsExactly("baseline", "candidate");
        assertThat(view.seeds()).containsExactly("42");
    }

    @Test
    void duplicateFactionInsideOneSetIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var sets = manifest.withArray("factionSets");
        var dupe = sets.arrayNode();
        dupe.add("ERASERS");
        dupe.add("ERASERS");
        dupe.add("WEAVERS");
        sets.add(dupe);

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void unknownFactionIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var sets = manifest.withArray("factionSets");
        var polluted = sets.arrayNode();
        polluted.add("ERASERS");
        polluted.add("PROPHETS");
        polluted.add("TIME_LORDS");
        sets.add(polluted);

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void factionSetSizeOutsideSelectedCountsIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("playerCounts").removeAll();

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void seedAboveUint64MaxIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var seeds = manifest.withArray("seeds");
        seeds.removeAll();
        seeds.add("18446744073709551616");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void duplicateVariantLabelsAreInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("variants").get(1).asObject().put("label", "baseline");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void malformedArtifactDigestIsManifestMismatch() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("variants").get(0).asObject().put("effectiveRulesDigest", "not-a-digest");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.MANIFEST_MISMATCH);
    }

    @Test
    void malformedServiceProvenanceIsManifestMismatch() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withObject("services").withObject("gameService").put("imageDigest", "latest");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.MANIFEST_MISMATCH);
    }

    @Test
    void credentialCarryingManifestIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("policies")
                .get(0)
                .asObject()
                .withObject("parameters")
                .put("api_password", "hunter2");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void machineLocalPathInManifestIsInvalid() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.withArray("policies")
                .get(0)
                .asObject()
                .withObject("parameters")
                .put("snapshot", "file:///home/designer/snapshot.json");

        assertThatThrownBy(() -> ExperimentValidator.validate(manifest))
                .isInstanceOf(ExperimentValidationException.class)
                .matches(ex -> ((ExperimentValidationException) ex).code() == ExperimentErrorCode.INVALID_EXPERIMENT);
    }

    @Test
    void ordinaryWordsContainingSecretMarkersAreAllowed() {
        var manifest = (ObjectNode) ExperimentManifests.valid().deepCopy();
        manifest.put("name", "secret-hitler variant");
        manifest.withArray("policies")
                .get(0)
                .asObject()
                .withObject("parameters")
                .put("tokenizer", "risk-averse");

        assertThat(ExperimentValidator.validate(manifest).name()).isEqualTo("secret-hitler variant");
    }
}
