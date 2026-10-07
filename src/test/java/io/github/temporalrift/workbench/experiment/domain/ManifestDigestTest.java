package io.github.temporalrift.workbench.experiment.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;

class ManifestDigestTest {

    @Test
    void digestIsLowercaseSha256Hex() {
        var digest = ManifestDigest.sha256Hex(ExperimentManifests.valid());

        assertThat(digest).matches("[a-f0-9]{64}");
    }

    @Test
    void keyOrderDoesNotChangeDigest() {
        var first = ExperimentManifests.valid();
        var reordered = (ObjectNode) first.deepCopy();
        var name = reordered.get("name").asString();
        reordered.remove("name");
        reordered.put("name", name);

        assertThat(ManifestDigest.sha256Hex(reordered)).isEqualTo(ManifestDigest.sha256Hex(first));
    }

    @Test
    void rulesChangeProducesDifferentDigest() {
        var threshold20 = ExperimentManifests.valid("42", "a".repeat(64), "b".repeat(64));
        var threshold22 = ExperimentManifests.valid("42", "c".repeat(63) + "0", "b".repeat(64));

        assertThat(ManifestDigest.sha256Hex(threshold22)).isNotEqualTo(ManifestDigest.sha256Hex(threshold20));
    }

    @Test
    void variantOrderChangesDigest() {
        var first = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var reordered = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var variants = reordered.withArray("variants");
        var baseline = variants.get(0).deepCopy();
        variants.remove(0);
        variants.add(baseline);

        assertThat(ManifestDigest.sha256Hex(reordered)).isNotEqualTo(ManifestDigest.sha256Hex(first));
    }

    @Test
    void factionSetOrderDoesNotChangeDigest() {
        var first = ExperimentManifests.valid();
        var reordered = (ObjectNode) ExperimentManifests.valid().deepCopy();
        var sets = reordered.withArray("factionSets");
        var head = sets.get(0).deepCopy();
        sets.remove(0);
        sets.add(head);

        assertThat(ManifestDigest.sha256Hex(reordered)).isEqualTo(ManifestDigest.sha256Hex(first));
    }
}
