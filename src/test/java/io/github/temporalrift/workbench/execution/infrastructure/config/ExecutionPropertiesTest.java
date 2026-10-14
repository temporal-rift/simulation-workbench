package io.github.temporalrift.workbench.execution.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;

class ExecutionPropertiesTest {

    private static final String LANE = "workbench.execution.lanes[0].";
    private static final String SECOND_LANE = "workbench.execution.lanes[1].";

    @Test
    void aLaneBindsTheBundleItServes() {
        var properties = bind(lane());

        assertThat(properties.lanes().getFirst().rulesDigest()).isEqualTo("a".repeat(64));
        assertThat(properties.lanes().getFirst().contentDigest()).isEqualTo("b".repeat(64));
    }

    @Test
    void aLaneWithoutARulesDigestFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.remove(LANE + "rules-digest");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].rules-digest is required");
    }

    @Test
    void aMalformedContentDigestFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.put(LANE + "content-digest", "B".repeat(64));

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].content-digest must be 64 lowercase hex characters");
    }

    @Test
    void aLaneBindsTheProvenanceOfItsBundle() {
        var values = lane();
        values.put(LANE + "rules-artifact-name", "threshold-22");

        var provenance = bind(values).lanes().getFirst().provenance();

        assertThat(provenance.rulesArtifact()).isEqualTo(new BundleProvenance.Artifact("c".repeat(64), "threshold-22"));
        assertThat(provenance.contentArtifact()).isEqualTo(new BundleProvenance.Artifact("d".repeat(64), null));
        assertThat(provenance.gameService())
                .isEqualTo(new BundleProvenance.ServiceBuild("sha256:" + "1".repeat(64), "abcdef1"));
        assertThat(provenance.readService())
                .isEqualTo(new BundleProvenance.ServiceBuild("sha256:" + "3".repeat(64), "abcdef3"));
        assertThat(provenance.contracts()).containsExactly(entry("action-api", "7.1.0"), entry("session-api", "3.0.0"));
    }

    @Test
    void aLaneWithoutAServiceImageDigestFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.remove(LANE + "services.game-service.image-digest");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].services.game-service.image-digest is required");
    }

    @Test
    void aLaneWithoutAnyServiceFailsNamingTheFirstMissingKey() {
        var values = lane();
        values.keySet().removeIf(key -> key.startsWith(LANE + "services."));

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].services.game-service.image-digest is required");
    }

    @Test
    void aMalformedSourceRevisionFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.put(LANE + "services.read-service.source-revision", "main");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].services.read-service.source-revision"
                        + " must be 7 to 64 lowercase hex characters");
    }

    @Test
    void aLaneWithoutAnArtifactDigestFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.remove(LANE + "content-artifact-digest");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].content-artifact-digest is required");
    }

    @Test
    void aMalformedContractVersionFailsNamingTheLaneAndTheModule() {
        var values = lane();
        values.put(LANE + "contracts.session-api", "latest");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage(
                        "workbench.execution.lanes[id=lane-1].contracts.session-api must be an exact semantic version");
    }

    @Test
    void aLaneWithoutContractsFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.keySet().removeIf(key -> key.startsWith(LANE + "contracts."));

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].contracts is required");
    }

    @Test
    void lanesServingOneBundleWithTheSameProvenanceBind() {
        var values = lane();
        values.putAll(secondLane());

        assertThat(bind(values).lanes()).hasSize(2);
    }

    @Test
    void lanesServingOneBundleWithDifferentProvenanceFailNamingBothLanesAndTheKey() {
        var values = lane();
        values.putAll(secondLane());
        values.put(SECOND_LANE + "services.read-service.source-revision", "fedcba9");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1] and workbench.execution.lanes[id=lane-2] serve the"
                        + " same rules and content digests but declare different"
                        + " services.read-service.source-revision");
    }

    @Test
    void lanesServingOneBundleMustAgreeOnTheirContractModules() {
        var values = lane();
        values.putAll(secondLane());
        values.put(SECOND_LANE + "contracts.projection-api", "10.0.0");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessageEndingWith("declare different contracts.projection-api");
    }

    @Test
    void lanesServingDifferentBundlesMayDeclareDifferentProvenance() {
        var values = lane();
        values.putAll(secondLane());
        values.put(SECOND_LANE + "rules-digest", "e".repeat(64));
        values.put(SECOND_LANE + "services.read-service.source-revision", "fedcba9");

        assertThat(bind(values).lanes()).hasSize(2);
    }

    @Test
    void aLaneWithoutAResetUrlIsNeverResetAndResetsAreBoundedByFiveMinutes() {
        var properties = bind(lane());

        assertThat(properties.lanes().getFirst().resetUrl()).isNull();
        assertThat(properties.resetTimeout()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void aLaneBindsItsResetUrl() {
        var values = lane();
        values.put(LANE + "reset-url", "http://lane-recycler:8080/lanes/lane-1/reset");

        assertThat(bind(values).lanes().getFirst().resetUrl())
                .isEqualTo("http://lane-recycler:8080/lanes/lane-1/reset");
    }

    @Test
    void aRelativeResetUrlFailsNamingTheLaneAndTheKey() {
        var values = lane();
        values.put(LANE + "reset-url", "/lanes/lane-1/reset");

        assertThatThrownBy(() -> bind(values))
                .isInstanceOf(BindException.class)
                .rootCause()
                .hasMessage("workbench.execution.lanes[id=lane-1].reset-url must be an absolute http or https URL");
    }

    @Test
    void aNonHttpResetUrlFails() {
        var values = lane();
        values.put(LANE + "reset-url", "ftp://recycler/lanes/lane-1/reset");

        assertThatThrownBy(() -> bind(values)).isInstanceOf(BindException.class);
    }

    private static Map<String, String> lane() {
        var values = new HashMap<String, String>();
        values.put(LANE + "id", "lane-1");
        values.put(LANE + "game-service-url", "http://game");
        values.put(LANE + "timeline-service-url", "http://timeline");
        values.put(LANE + "read-service-url", "http://read");
        values.put(LANE + "operator-token", "operator");
        values.put(LANE + "rules-digest", "a".repeat(64));
        values.put(LANE + "content-digest", "b".repeat(64));
        values.put(LANE + "rules-artifact-digest", "c".repeat(64));
        values.put(LANE + "content-artifact-digest", "d".repeat(64));
        values.put(LANE + "services.game-service.image-digest", "sha256:" + "1".repeat(64));
        values.put(LANE + "services.game-service.source-revision", "abcdef1");
        values.put(LANE + "services.timeline-service.image-digest", "sha256:" + "2".repeat(64));
        values.put(LANE + "services.timeline-service.source-revision", "abcdef2");
        values.put(LANE + "services.read-service.image-digest", "sha256:" + "3".repeat(64));
        values.put(LANE + "services.read-service.source-revision", "abcdef3");
        values.put(LANE + "contracts.session-api", "3.0.0");
        values.put(LANE + "contracts.action-api", "7.1.0");
        return values;
    }

    /** A second lane declaring exactly what {@link #lane()} declares. */
    private static Map<String, String> secondLane() {
        var values = new HashMap<String, String>();
        lane().forEach((key, value) -> values.put(key.replace(LANE, SECOND_LANE), value));
        values.put(SECOND_LANE + "id", "lane-2");
        return values;
    }

    private static ExecutionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("workbench.execution", ExecutionProperties.class);
    }
}
