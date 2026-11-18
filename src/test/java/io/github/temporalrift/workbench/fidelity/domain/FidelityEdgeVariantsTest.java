package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
    private static final String WRITINGS_PER_ERA = "game.rules.foresight.writings-per-era";
    private static final String FACTION_CLAIMS = "game.rules.stabilization.faction-claims";
    private static final String TAPESTRY_ABSORBS = "game.rules.tapestry-absorbs-chain-conflict";
    private static final String FATE_CONTRADICTION = "game.rules.paradox.fate-contradiction";
    private static final String MISSED_WITH_RALLY =
            "game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED_WITH_RALLY";
    private static final String DECLARED_MISSED = "game.rules.scoring.score-deltas.DECLARED_OUTCOME_MISSED";
    private static final String PARADOX_STABILIZED = "game.rules.scoring.score-deltas.PARADOX_STABILIZED";
    private static final String PROPHET_OBJECTIVE = "game.rules.victory.prophet-written-resolutions";
    private static final List<String> SPACE_FOUR_SETTINGS = List.of(
            WRITINGS_PER_ERA,
            FACTION_CLAIMS,
            TAPESTRY_ABSORBS,
            FATE_CONTRADICTION,
            MISSED_WITH_RALLY,
            DECLARED_MISSED,
            PARADOX_STABILIZED);

    /** A row of a record's override table: key, baseline, {@code low} and {@code high}, each value maybe marked †. */
    private static final Pattern TABLE_ROW =
            Pattern.compile("\\| `(game\\.rules\\.[^`]+)` \\| (\\S+) \\| (\\S+)(?: †)? \\| (\\S+)(?: †)? \\|");

    private final EdgeVariantCatalog catalog = ConfiguredEdgeVariants.load(new ObjectMapper());

    private static ParameterSpace space(int version) {
        return VariantRulesFixtures.spaces().find(version).orElseThrow();
    }

    @Test
    void edgeVariantsAreShippedForParameterSpacesOneToFive() {
        assertThat(catalog.all())
                .extracting(EdgeVariants::parameterSpaceVersion)
                .containsExactly(1, 2, 3, 4, 5);
        assertThat(catalog.newest().parameterSpaceVersion()).isEqualTo(5);
        assertThat(catalog.forSpace(1).parameterSpaceVersion()).isEqualTo(1);
    }

    @Test
    void spaceFiveTakesTheNineNewSettingsAtOppositeExtremes() {
        var five = catalog.forSpace(5);

        assertThat(five.low())
                .containsEntry("game.rules.events-per-era.3", ParameterValue.of(new BigDecimal("3")))
                .containsEntry("game.rules.events-per-era.4", ParameterValue.of(new BigDecimal("3")))
                .containsEntry("game.rules.events-per-era.5", ParameterValue.of(new BigDecimal("3")))
                .containsEntry("game.rules.scoring.erased-outcome-voids-bets", ParameterValue.of(false))
                .containsEntry("game.rules.paradox.detonate-contests-stabilize", ParameterValue.of(false))
                .containsEntry("game.rules.scoring.score-deltas.PARADOX_DETONATED", ParameterValue.of(BigDecimal.ZERO))
                .containsEntry(
                        "game.rules.scoring.score-deltas.ANNIHILATION_WASTED", ParameterValue.of(BigDecimal.ZERO))
                .containsEntry("game.rules.victory.score-tiebreak", ParameterValue.of(false))
                .containsEntry("game.rules.victory.final-era-objective-discount", ParameterValue.of(BigDecimal.ZERO));
        assertThat(five.high())
                .containsEntry("game.rules.events-per-era.3", ParameterValue.of(new BigDecimal("5")))
                .containsEntry("game.rules.events-per-era.4", ParameterValue.of(new BigDecimal("5")))
                .containsEntry("game.rules.events-per-era.5", ParameterValue.of(new BigDecimal("5")))
                .containsEntry("game.rules.scoring.erased-outcome-voids-bets", ParameterValue.of(true))
                .containsEntry("game.rules.paradox.detonate-contests-stabilize", ParameterValue.of(true))
                .containsEntry(
                        "game.rules.scoring.score-deltas.PARADOX_DETONATED", ParameterValue.of(new BigDecimal("-3")))
                .containsEntry(
                        "game.rules.scoring.score-deltas.ANNIHILATION_WASTED", ParameterValue.of(new BigDecimal("-2")))
                .containsEntry("game.rules.victory.score-tiebreak", ParameterValue.of(true))
                .containsEntry(
                        "game.rules.victory.final-era-objective-discount", ParameterValue.of(new BigDecimal("2")));
    }

    @Test
    void spaceFiveCarriesEverySpaceFourValueOver() {
        var four = catalog.forSpace(4);
        var five = catalog.forSpace(5);

        assertThat(five.low())
                .containsAllEntriesOf(four.low())
                .hasSize(four.low().size() + 9);
        assertThat(five.high())
                .containsAllEntriesOf(four.high())
                .hasSize(four.high().size() + 9);
    }

    @Test
    void aSpaceWithoutEdgeVariantsIsRefused() {
        assertThatThrownBy(() -> catalog.forSpace(6))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("parameter space 6");
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
    void spaceFourTakesTheSevenNewSettingsAtOppositeExtremes() {
        var four = catalog.forSpace(4);

        assertThat(four.low())
                .containsEntry(WRITINGS_PER_ERA, ParameterValue.of(new BigDecimal("1")))
                .containsEntry(FACTION_CLAIMS, ParameterValue.of(false))
                .containsEntry(TAPESTRY_ABSORBS, ParameterValue.of(false))
                .containsEntry(FATE_CONTRADICTION, ParameterValue.of(false))
                .containsEntry(MISSED_WITH_RALLY, ParameterValue.of(new BigDecimal("-4")))
                .containsEntry(DECLARED_MISSED, ParameterValue.of(new BigDecimal("-3")))
                .containsEntry(PARADOX_STABILIZED, ParameterValue.of(new BigDecimal("-3")));
        assertThat(four.high())
                .containsEntry(WRITINGS_PER_ERA, ParameterValue.of(new BigDecimal("3")))
                .containsEntry(FACTION_CLAIMS, ParameterValue.of(true))
                .containsEntry(TAPESTRY_ABSORBS, ParameterValue.of(true))
                .containsEntry(FATE_CONTRADICTION, ParameterValue.of(true))
                .containsEntry(MISSED_WITH_RALLY, ParameterValue.of(new BigDecimal("0")))
                .containsEntry(DECLARED_MISSED, ParameterValue.of(new BigDecimal("0")))
                .containsEntry(PARADOX_STABILIZED, ParameterValue.of(new BigDecimal("0")));
    }

    @Test
    void spaceFourMovesTheProphetObjectiveToItsNewRangeEdges() {
        var four = catalog.forSpace(4);

        assertThat(four.low()).containsEntry(PROPHET_OBJECTIVE, ParameterValue.of(new BigDecimal("2")));
        assertThat(four.high()).containsEntry(PROPHET_OBJECTIVE, ParameterValue.of(new BigDecimal("4")));
    }

    @Test
    void spaceFourCarriesEverySpaceThreeValueOverExceptTheProphetObjective() {
        var three = catalog.forSpace(3);
        var four = catalog.forSpace(4);

        for (var carried : List.of(Map.entry(three.low(), four.low()), Map.entry(three.high(), four.high()))) {
            var expected = new TreeMap<>(carried.getKey());
            expected.remove(PROPHET_OBJECTIVE);
            assertThat(carried.getValue())
                    .containsAllEntriesOf(expected)
                    .hasSize(carried.getKey().size() + SPACE_FOUR_SETTINGS.size());
        }
    }

    @Test
    void spaceThreeNamesNoneOfTheSpaceFourSettings() {
        var three = catalog.forSpace(3);

        for (var overrides : List.of(three.low(), three.high())) {
            assertThat(overrides).doesNotContainKeys(SPACE_FOUR_SETTINGS.toArray(String[]::new));
        }
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
