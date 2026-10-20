package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.rules.ConfiguredBaselineRules.Declaration;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

class ConfiguredBaselineRulesTest {

    private static final String LANE_1 = "workbench.execution.lanes[id=lane-1].";
    private static final String LANE_2 = "workbench.execution.lanes[id=lane-2].";
    private static final String ARTIFACT = "c".repeat(64);
    private static final String SMALL = "{\"schemaVersion\": 1, \"properties\": {\"game.rules.a\": 1}}";

    @TempDir
    Path directory;

    private final ObjectMapper mapper = new ObjectMapper();

    private static List<Declaration> declarations(Declaration... declarations) {
        return List.of(declarations);
    }

    private Path write(String name, String text) {
        try {
            return Files.writeString(directory.resolve(name), text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String digestOf(String text) {
        return io.github.temporalrift.workbench.experiment.EffectiveRules.parse(new ObjectMapper().readTree(text))
                .digest();
    }

    @Test
    void servesTheDocumentAVariantNamesByItsRulesArtifact() {
        var document = write("rules.json", VariantRulesFixtures.baselineText());
        var baseline = VariantRulesFixtures.baseline();

        var catalog = new ConfiguredBaselineRules(
                List.of(new Declaration(LANE_1, ARTIFACT, baseline.digest(), document)), mapper);

        var found = catalog.find(ARTIFACT).orElseThrow();
        assertThat(found.document()).isEqualTo(VariantRulesFixtures.baselineText());
        assertThat(found.rules().digest()).isEqualTo(baseline.digest());
    }

    @Test
    void anArtifactNoLaneDeclaresIsNotServed() {
        var catalog = new ConfiguredBaselineRules(List.of(), mapper);

        assertThat(catalog.find(ARTIFACT)).isEmpty();
    }

    @Test
    void aDocumentWhoseDigestDiffersFromTheDeclaredOneFailsNamingTheLane() {
        var document = write("rules.json", SMALL);
        var declaration = new Declaration(LANE_1, ARTIFACT, "f".repeat(64), document);

        assertThatThrownBy(() -> new ConfiguredBaselineRules(declarations(declaration), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LANE_1 + "rules-document")
                .hasMessageContaining(digestOf(SMALL))
                .hasMessageContaining("f".repeat(64));
    }

    @Test
    void aMissingDocumentFailsNamingTheLane() {
        var declaration = new Declaration(LANE_1, ARTIFACT, "f".repeat(64), directory.resolve("absent.json"));

        assertThatThrownBy(() -> new ConfiguredBaselineRules(declarations(declaration), mapper))
                .isInstanceOf(UncheckedIOException.class)
                .hasMessageContaining(LANE_1 + "rules-document");
    }

    @Test
    void aDocumentThatIsNotAnEffectiveRulesDocumentFailsNamingTheLane() {
        var declaration = new Declaration(LANE_1, ARTIFACT, "f".repeat(64), write("rules.json", "{\"a\": 1}"));

        assertThatThrownBy(() -> new ConfiguredBaselineRules(declarations(declaration), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LANE_1 + "rules-document is not an EffectiveRules document");
    }

    @Test
    void aSyntacticallyBrokenDocumentFailsNamingTheLane() {
        var declaration = new Declaration(LANE_1, ARTIFACT, "f".repeat(64), write("rules.json", "{"));

        assertThatThrownBy(() -> new ConfiguredBaselineRules(declarations(declaration), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LANE_1 + "rules-document");
    }

    @Test
    void lanesOfOneArtifactMayShareItsDocument() {
        var digest = digestOf(SMALL);
        var first = new Declaration(LANE_1, ARTIFACT, digest, write("one.json", SMALL));
        var second = new Declaration(LANE_2, ARTIFACT, digest, write("two.json", SMALL));

        var catalog = new ConfiguredBaselineRules(List.of(first, second), mapper);

        assertThat(catalog.find(ARTIFACT)).isPresent();
    }

    @Test
    void lanesOfOneArtifactCannotDeclareDifferentDocuments() {
        var other = "{\"schemaVersion\": 1, \"properties\": {\"game.rules.a\": 2}}";
        var first = new Declaration(LANE_1, ARTIFACT, digestOf(SMALL), write("one.json", SMALL));
        var second = new Declaration(LANE_2, ARTIFACT, digestOf(other), write("two.json", other));

        assertThatThrownBy(() -> new ConfiguredBaselineRules(declarations(first, second), mapper))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(LANE_2 + "rules-document")
                .hasMessageContaining(ARTIFACT);
    }
}
