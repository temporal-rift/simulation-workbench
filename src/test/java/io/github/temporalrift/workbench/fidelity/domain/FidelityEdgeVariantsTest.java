package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantMaterializer;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;

/** The edge variants validate against parameter space 1 and override every one of its parameters. */
class FidelityEdgeVariantsTest {

    private final EdgeVariants variants = ConfiguredEdgeVariants.load(new ObjectMapper());
    private final ParameterSpace space = VariantRulesFixtures.spaces().find(1).orElseThrow();

    @Test
    void bothSetsValidateAgainstTheParameterSpace() {
        assertThat(variants.parameterSpaceVersion()).isEqualTo(1);
        for (var overrides : List.of(variants.low(), variants.high())) {
            var materialized = VariantMaterializer.materialize(VariantRulesFixtures.baseline(), space, overrides);
            assertThat(materialized.rules().digest()).hasSize(64);
        }
    }

    @Test
    void bothSetsOverrideEveryParameterOfTheSpace() {
        var keys = space.parameters().stream().map(Parameter::key).collect(Collectors.toSet());

        assertThat(variants.low().keySet()).isEqualTo(keys);
        assertThat(variants.high().keySet()).isEqualTo(keys);
    }
}
