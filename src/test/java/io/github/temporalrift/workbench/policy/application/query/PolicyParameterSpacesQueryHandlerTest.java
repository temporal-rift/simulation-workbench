package io.github.temporalrift.workbench.policy.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.domain.PolicyParameterSpaceNotFoundException;

class PolicyParameterSpacesQueryHandlerTest {

    private final PolicyParameterSpacesQueryHandler handler =
            new PolicyParameterSpacesQueryHandler(new BaselinePolicyCatalog());

    @Test
    void theServedVersionDeclaresEveryParameterAndEachFactionsSubset() {
        var space = handler.get(1);

        assertThat(space.policyId()).isEqualTo("faction-search");
        assertThat(space.parameters()).isNotEmpty().allSatisfy(parameter -> {
            assertThat(parameter.min()).isLessThanOrEqualTo(parameter.defaultValue());
            assertThat(parameter.defaultValue()).isLessThanOrEqualTo(parameter.max());
            assertThat(parameter.step()).isPositive();
        });
        assertThat(space.searchedSubsets())
                .containsOnlyKeys("ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS");
        assertThat(space.searchedSubsets().values())
                .allSatisfy(keys -> assertThat(keys).hasSizeBetween(12, 16));
    }

    @Test
    void anUnknownVersionIsNotFound() {
        assertThatThrownBy(() -> handler.get(2)).isInstanceOf(PolicyParameterSpaceNotFoundException.class);
    }

    @Test
    void theBundleListingCarriesTheParameterDefinitionsOfFactionSearchOnly() {
        var descriptors = new BaselinePolicyCatalog().list();

        assertThat(descriptors)
                .filteredOn(descriptor -> descriptor.id().equals("faction-search"))
                .singleElement()
                .satisfies(descriptor -> assertThat(descriptor.parameterDefinitions())
                        .hasSameSizeAs(handler.get(1).parameters()));
        assertThat(descriptors)
                .filteredOn(descriptor -> !descriptor.id().equals("faction-search"))
                .allSatisfy(descriptor ->
                        assertThat(descriptor.parameterDefinitions()).isEmpty());
    }
}
