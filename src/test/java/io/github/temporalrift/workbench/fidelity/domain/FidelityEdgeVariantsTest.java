package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantMaterializer;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/** The edge variants of each parameter space validate against it and override every one of its parameters. */
class FidelityEdgeVariantsTest {

    private static final String EARLIEST_ROUND = "game.rules.annihilate-earliest-round";
    private static final String WRITTEN_SHIELDS = "game.rules.scoring.written-outcome-shields-annihilation";
    private static final String ERASED_WEIGHT = "game.rules.probability.erased-weight-to-written-outcome";
    private static final String REWRITE_ROUND = "game.rules.rewrite-latest-round";
    private static final String SECRET_MISSED = "game.rules.scoring.score-deltas.SECRET_OUTCOME_MISSED";
    private static final String EXPOSE_DECLARED = "game.rules.scoring.expose-requires-declared-event";
    private static final String ANNIHILATION_CONSEQUENCE = "game.rules.scoring.annihilation-requires-consequence";

    /** A row of a record's override table: key, baseline, {@code low} and {@code high}, each value maybe marked †. */
    private static final Pattern TABLE_ROW =
            Pattern.compile("\\| `(game\\.rules\\.[^`]+)` \\| (\\S+) \\| (\\S+)(?: †)? \\| (\\S+)(?: †)? \\|");

    private final EdgeVariantCatalog catalog = ConfiguredEdgeVariants.load(new ObjectMapper());

    private static ParameterSpace space(int version) {
        return VariantRulesFixtures.spaces().find(version).orElseThrow();
    }

    @Test
    void edgeVariantsAreShippedForParameterSpacesOneTwoAndThree() {
        assertThat(catalog.all())
                .extracting(EdgeVariants::parameterSpaceVersion)
                .containsExactly(1, 2, 3);
        assertThat(catalog.newest().parameterSpaceVersion()).isEqualTo(3);
        assertThat(catalog.forSpace(1).parameterSpaceVersion()).isEqualTo(1);
    }

    @Test
    void aSpaceWithoutEdgeVariantsIsRefused() {
        assertThatThrownBy(() -> catalog.forSpace(4))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("parameter space 4");
    }

    @Test
    void bothSetsOfEachSpaceValidateAgainstThatSpace() {
        for (var variants : catalog.all()) {
            var space = space(variants.parameterSpaceVersion());
            for (var overrides : List.of(variants.low(), variants.high())) {
                var materialized = VariantMaterializer.materialize(VariantRulesFixtures.baseline(), space, overrides);
                assertThat(materialized.rules().digest()).hasSize(64);
            }
        }
    }

    @Test
    void bothSetsOfEachSpaceOverrideEveryParameterOfThatSpace() {
        for (var variants : catalog.all()) {
            var keys = space(variants.parameterSpaceVersion()).parameters().stream()
                    .map(Parameter::key)
                    .collect(Collectors.toSet());

            assertThat(variants.low().keySet()).isEqualTo(keys);
            assertThat(variants.high().keySet()).isEqualTo(keys);
        }
    }

    @Test
    void spaceTwoTakesTheThreeNewRulesAtOppositeExtremes() {
        var two = catalog.forSpace(2);

        assertThat(two.low())
                .containsEntry(EARLIEST_ROUND, ParameterValue.of(new BigDecimal("1")))
                .containsEntry(WRITTEN_SHIELDS, ParameterValue.of(false))
                .containsEntry(ERASED_WEIGHT, ParameterValue.of(false));
        assertThat(two.high())
                .containsEntry(EARLIEST_ROUND, ParameterValue.of(new BigDecimal("3")))
                .containsEntry(WRITTEN_SHIELDS, ParameterValue.of(true))
                .containsEntry(ERASED_WEIGHT, ParameterValue.of(true));
    }

    @Test
    void spaceTwoCarriesEverySpaceOneValueOverUnchanged() {
        var one = catalog.forSpace(1);
        var two = catalog.forSpace(2);

        assertThat(two.low()).containsAllEntriesOf(one.low()).hasSize(one.low().size() + 3);
        assertThat(two.high())
                .containsAllEntriesOf(one.high())
                .hasSize(one.high().size() + 3);
    }

    @Test
    void spaceThreeTakesTheFourNewSettingsAtOppositeExtremes() {
        var three = catalog.forSpace(3);

        assertThat(three.low())
                .containsEntry(REWRITE_ROUND, ParameterValue.of(new BigDecimal("1")))
                .containsEntry(SECRET_MISSED, ParameterValue.of(new BigDecimal("-3")))
                .containsEntry(EXPOSE_DECLARED, ParameterValue.of(false))
                .containsEntry(ANNIHILATION_CONSEQUENCE, ParameterValue.of(false));
        assertThat(three.high())
                .containsEntry(REWRITE_ROUND, ParameterValue.of(new BigDecimal("3")))
                .containsEntry(SECRET_MISSED, ParameterValue.of(new BigDecimal("0")))
                .containsEntry(EXPOSE_DECLARED, ParameterValue.of(true))
                .containsEntry(ANNIHILATION_CONSEQUENCE, ParameterValue.of(true));
    }

    @Test
    void spaceThreeCarriesEverySpaceTwoValueOverUnchanged() {
        var two = catalog.forSpace(2);
        var three = catalog.forSpace(3);

        assertThat(three.low())
                .containsAllEntriesOf(two.low())
                .hasSize(two.low().size() + 4);
        assertThat(three.high())
                .containsAllEntriesOf(two.high())
                .hasSize(two.high().size() + 4);
    }

    @Test
    void spaceTwoNamesNoneOfTheSpaceThreeSettings() {
        var two = catalog.forSpace(2);

        for (var overrides : List.of(two.low(), two.high())) {
            assertThat(overrides)
                    .doesNotContainKeys(REWRITE_ROUND, SECRET_MISSED, EXPOSE_DECLARED, ANNIHILATION_CONSEQUENCE);
        }
    }

    @Test
    void spaceOneNamesNoneOfTheNewRules() {
        var one = catalog.forSpace(1);

        for (var overrides : List.of(one.low(), one.high())) {
            assertThat(overrides).doesNotContainKeys(EARLIEST_ROUND, WRITTEN_SHIELDS, ERASED_WEIGHT);
        }
    }

    @Test
    void theRecordOfEachSpaceAndItsResourceAgree() throws IOException {
        for (var variants : catalog.all()) {
            var document = Files.readAllLines(Path.of("docs/fidelity/v" + variants.parameterSpaceVersion() + ".md"));
            var rows = new TreeMap<String, List<String>>();
            for (var line : document) {
                var row = TABLE_ROW.matcher(line);
                if (row.matches()) {
                    rows.put(row.group(1), List.of(row.group(2), row.group(3), row.group(4)));
                }
            }

            assertThat(rows.keySet()).isEqualTo(variants.low().keySet());
            rows.forEach((key, cells) -> {
                assertThat(cells.get(1))
                        .as("low " + key)
                        .isEqualTo(shown(variants.low().get(key)));
                assertThat(cells.get(2))
                        .as("high " + key)
                        .isEqualTo(shown(variants.high().get(key)));
            });
        }
    }

    private static String shown(ParameterValue value) {
        return value.text();
    }

    @Test
    void anEmptyCatalogAndAnAmbiguousOneAreRefused() {
        var variants = new EdgeVariants(1, new TreeMap<>(), new TreeMap<>());

        var none = List.<EdgeVariants>of();
        var twice = List.of(variants, variants);

        assertThatThrownBy(() -> new EdgeVariantCatalog(none)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EdgeVariantCatalog(twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("parameter space 1");
    }
}
