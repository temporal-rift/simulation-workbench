package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** A campaign stored on the first parameter space still resolves to the very rules it was run with. */
class SpaceOneReplayTest {

    /** The digest of the rules the variant below made on the first space, before the second space was added. */
    private static final String SPACE_ONE_DIGEST = "aa37cff24f73efead346fb6fcf79ad02e0b4e3668b1c7b7494291371e0868b76";

    @Test
    void aVariantOfTheFirstSpaceMaterializesToTheSameDigestAsBefore() {
        var space = VariantRulesFixtures.spaces().find(1).orElseThrow();
        var overrides = Map.of(
                "game.rules.rewrite-latest-round", ParameterValue.of(new BigDecimal("3")),
                "game.rules.probability.thread-shift", ParameterValue.of(new BigDecimal("15")),
                "game.rules.victory.final-era-objective-discount", ParameterValue.of(new BigDecimal("2")));

        var rules = VariantMaterializer.materialize(VariantRulesFixtures.spaceOneBaseline(), space, overrides)
                .rules();

        assertThat(rules.digest()).isEqualTo(SPACE_ONE_DIGEST);
    }
}
