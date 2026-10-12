package io.github.temporalrift.workbench.execution.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

class ExecutionPropertiesTest {

    private static final String LANE = "workbench.execution.lanes[0].";

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

    private static Map<String, String> lane() {
        var values = new HashMap<String, String>();
        values.put(LANE + "id", "lane-1");
        values.put(LANE + "game-service-url", "http://game");
        values.put(LANE + "timeline-service-url", "http://timeline");
        values.put(LANE + "read-service-url", "http://read");
        values.put(LANE + "operator-token", "operator");
        values.put(LANE + "rules-digest", "a".repeat(64));
        values.put(LANE + "content-digest", "b".repeat(64));
        return values;
    }

    private static ExecutionProperties bind(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values))
                .bindOrCreate("workbench.execution", ExecutionProperties.class);
    }
}
