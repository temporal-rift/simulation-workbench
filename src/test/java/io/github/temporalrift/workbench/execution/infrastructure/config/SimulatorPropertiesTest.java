package io.github.temporalrift.workbench.execution.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class SimulatorPropertiesTest {

    private static final String PREFIX = "workbench.simulator.";

    @Test
    void theSimulatorIsDisabledByDefaultAndNeedsNoSettingWhileItIs() {
        var properties = bind(Map.of());

        assertThat(properties.slots()).isZero();
        assertThat(properties.enabled()).isFalse();
    }

    @Test
    void slotsBindWithTheJarsTheRulesAndTheDeclaredBundle() {
        var properties = bind(simulator());

        assertThat(properties.enabled()).isTrue();
        assertThat(properties.slots()).isEqualTo(2);
        assertThat(properties.gameServiceJar()).isEqualTo(Path.of("game.jar"));
        assertThat(properties.bots()).isEqualTo(6);
        assertThat(properties.bundle().digests().rulesDigest()).isEqualTo("a".repeat(64));
        assertThat(properties.bundle().sortedContracts()).containsEntry("session-api", "3.0.0");
        assertThat(properties.bundle().sourceRevisions().timelineService()).isEqualTo("2222222");
    }

    @ParameterizedTest
    @ValueSource(strings = {"game-service-jar", "timeline-service-jar", "read-service-jar", "rules-file"})
    void aSlotWithoutAPathFailsNamingTheKey(String key) {
        var values = simulator();
        values.remove(PREFIX + key);

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage(PREFIX + key + " is required");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "rules-digest",
                "rules-document",
                "content-digest",
                "rules-artifact-digest",
                "content-artifact-digest",
                "source-revisions.game-service",
                "source-revisions.timeline-service",
                "source-revisions.read-service"
            })
    void aBundleWithoutARequiredKeyFailsNamingTheKey(String key) {
        var values = simulator();
        values.remove(PREFIX + "bundle." + key);

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage(PREFIX + "bundle." + key + " is required");
    }

    @Test
    void aBundleWithoutContractVersionsFails() {
        var values = simulator();
        values.remove(PREFIX + "bundle.contracts.session-api");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage(PREFIX + "bundle.contracts is required");
    }

    @Test
    void withoutDeclaredBotIdsTheSeatsGetDerivedOnes() {
        var properties = bind(simulator());

        assertThat(properties.botPlayerIds()).isEmpty();
        assertThat(properties.effectiveBotPlayerIds()).hasSize(6).doesNotHaveDuplicates();
    }

    @Test
    void declaredBotIdsReplaceTheDerivedOnes() {
        var values = simulator();
        values.put(PREFIX + "bots", "3");
        values.put(
                PREFIX + "bot-player-ids",
                "00000000-0000-4000-8000-000000000001,"
                        + "00000000-0000-4000-8000-000000000002,00000000-0000-4000-8000-000000000003");

        var properties = bind(values);

        assertThat(properties.effectiveBotPlayerIds())
                .extracting(java.util.UUID::toString)
                .containsExactly(
                        "00000000-0000-4000-8000-000000000001",
                        "00000000-0000-4000-8000-000000000002",
                        "00000000-0000-4000-8000-000000000003");
    }

    @Test
    void tooFewDeclaredBotIdsFailNamingTheKey() {
        var values = simulator();
        values.put(PREFIX + "bot-player-ids", "00000000-0000-4000-8000-000000000001");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageContaining(PREFIX + "bot-player-ids must declare at least 6 ids");
    }

    @Test
    void aRepeatedDeclaredBotIdFails() {
        var values = simulator();
        values.put(PREFIX + "bots", "2");
        values.put(
                PREFIX + "bot-player-ids",
                "00000000-0000-4000-8000-000000000001," + "00000000-0000-4000-8000-000000000001");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage(PREFIX + "bot-player-ids must not repeat an id");
    }

    @Test
    void aNegativeNumberOfSlotsFails() {
        var values = Map.of(PREFIX + "slots", "-1");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage(PREFIX + "slots must not be negative");
    }

    private static HashMap<String, String> simulator() {
        var values = new HashMap<String, String>();
        values.put(PREFIX + "slots", "2");
        values.put(PREFIX + "game-service-jar", "game.jar");
        values.put(PREFIX + "timeline-service-jar", "timeline.jar");
        values.put(PREFIX + "read-service-jar", "read.jar");
        values.put(PREFIX + "rules-file", "rules.yml");
        values.put(PREFIX + "bundle.rules-digest", "a".repeat(64));
        values.put(PREFIX + "bundle.rules-document", "effective-rules.json");
        values.put(PREFIX + "bundle.content-digest", "b".repeat(64));
        values.put(PREFIX + "bundle.rules-artifact-digest", "c".repeat(64));
        values.put(PREFIX + "bundle.content-artifact-digest", "d".repeat(64));
        values.put(PREFIX + "bundle.source-revisions.game-service", "1111111");
        values.put(PREFIX + "bundle.source-revisions.timeline-service", "2222222");
        values.put(PREFIX + "bundle.source-revisions.read-service", "3333333");
        values.put(PREFIX + "bundle.contracts.session-api", "3.0.0");
        return values;
    }

    private static SimulatorProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("workbench.simulator", SimulatorProperties.class);
    }
}
