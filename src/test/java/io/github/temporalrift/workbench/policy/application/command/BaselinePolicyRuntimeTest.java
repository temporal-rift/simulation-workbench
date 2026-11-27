package io.github.temporalrift.workbench.policy.application.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.domain.baseline.SearchParameters;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;

class BaselinePolicyRuntimeTest {

    private final BaselinePolicyRuntime runtime = new BaselinePolicyRuntime(null);

    @Test
    void aParameterizedBundleIsFoundWithAndWithoutParameters() {
        var scan = SearchParameters.cardKey(Faction.WEAVERS, CardType.SCAN);

        assertThat(runtime.policy("faction-search", "1.0.0")).isPresent();
        assertThat(runtime.policy("faction-search", "1.0.0", Map.of(scan, 8))).isPresent();
    }

    @Test
    void aBaselineIgnoresParametersAndAnUnknownBundleIsAbsent() {
        assertThat(runtime.policy("faction-spread", "1.0.0", Map.of())).isPresent();
        assertThat(runtime.policy("nope", "1.0.0", Map.of())).isEmpty();
    }
}
