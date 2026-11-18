package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

/** Which parameter spaces are served: the one place a new space changes, so no version's own test has to. */
class ParameterSpaceCatalogTest {

    @Test
    void everySpaceFromOneToTheNewestIsServedInOrder() {
        var versions = VariantRulesFixtures.spaces().all().stream()
                .map(ParameterSpace::version)
                .toList();

        assertThat(versions)
                .isEqualTo(IntStream.rangeClosed(1, versions.getLast()).boxed().toList());
    }
}
