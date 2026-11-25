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

/** The one set of edge variants validates against the served parameter space and overrides every parameter of it. */
class FidelityEdgeVariantsTest {

    private static final String LINK_MISSED = "game.rules.scoring.score-deltas.CHAIN_LINK_MISSED";
    private static final String ERASER_5 = "game.rules.victory.eraser-annihilations-by-player-count.5";
    private static final String ACTIVIST_5 = "game.rules.victory.activist-consecutive-eras-by-player-count.5";
    private static final String EVENTS_3 = "game.rules.events-per-era.3";
    private static final String CONTEST = "game.rules.paradox.detonate-contests-stabilize";
    private static final String DISCOUNT = "game.rules.victory.final-era-objective-discount";

    /** A row of the record's override table: key, baseline, {@code low} and {@code high}, each maybe marked †. */
    private static final Pattern TABLE_ROW =
            Pattern.compile("\\| `(game\\.rules\\.[^`]+)` \\| (\\S+) \\| (\\S+)( †)? \\| (\\S+)( †)? \\|");

    private final EdgeVariantCatalog catalog = ConfiguredEdgeVariants.load(new ObjectMapper());
    private final ParameterSpace space = VariantRulesFixtures.spaces().all().getFirst();

    @Test
    void oneSetIsShippedForTheServedSpace() {
        assertThat(catalog.all()).hasSize(1);
        assertThat(catalog.newest().parameterSpaceVersion()).isEqualTo(space.version());
        assertThat(catalog.forSpace(space.version())).isSameAs(catalog.newest());
    }

    @Test
    void aSpaceWithoutEdgeVariantsIsRefused() {
        var other = space.version() + 1;

        assertThatThrownBy(() -> catalog.forSpace(other))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("parameter space " + other);
    }

    @Test
    void theRebalanceSettingsSitAtOppositeExtremes() {
        var variants = catalog.newest();

        assertThat(variants.low())
                .containsEntry(LINK_MISSED, ParameterValue.of(new BigDecimal("-2")))
                .containsEntry(ERASER_5, ParameterValue.of(new BigDecimal("3")))
                .containsEntry(ACTIVIST_5, ParameterValue.of(false))
                .containsEntry(EVENTS_3, ParameterValue.of(new BigDecimal("3")))
                .containsEntry(CONTEST, ParameterValue.of(false))
                .containsEntry(DISCOUNT, ParameterValue.of(BigDecimal.ZERO));
        assertThat(variants.high())
                .containsEntry(LINK_MISSED, ParameterValue.of(BigDecimal.ZERO))
                .containsEntry(ERASER_5, ParameterValue.of(new BigDecimal("5")))
                .containsEntry(ACTIVIST_5, ParameterValue.of(true))
                .containsEntry(EVENTS_3, ParameterValue.of(new BigDecimal("5")))
                .containsEntry(CONTEST, ParameterValue.of(true))
                .containsEntry(DISCOUNT, ParameterValue.of(new BigDecimal("2")));
    }

    @Test
    void bothSetsValidateAgainstTheSpace() {
        var variants = catalog.newest();
        for (var overrides : List.of(variants.low(), variants.high())) {
            var materialized = VariantMaterializer.materialize(VariantRulesFixtures.baseline(), space, overrides);
            assertThat(materialized.rules().digest()).hasSize(64);
        }
    }

    @Test
    void bothSetsOverrideEveryParameterOfTheSpace() {
        var variants = catalog.newest();
        var keys = space.parameters().stream().map(Parameter::key).collect(Collectors.toSet());

        assertThat(variants.low().keySet()).isEqualTo(keys);
        assertThat(variants.high().keySet()).isEqualTo(keys);
    }

    @Test
    void theRecordAndItsResourceAgree() throws IOException {
        var variants = catalog.newest();
        var document = Files.readAllLines(Path.of("docs/fidelity/current.md"));
        var rows = new TreeMap<String, List<String>>();
        var marks = new TreeMap<String, List<Boolean>>();
        for (var line : document) {
            var row = TABLE_ROW.matcher(line);
            if (row.matches()) {
                rows.put(row.group(1), List.of(row.group(2), row.group(3), row.group(5)));
                marks.put(row.group(1), List.of(row.group(4) != null, row.group(6) != null));
            }
        }

        assertThat(rows.keySet()).isEqualTo(variants.low().keySet());
        rows.forEach((key, cells) -> {
            space.parameter(key)
                    .ifPresent(parameter ->
                            assertThat(cells.get(0)).as("baseline " + key).isEqualTo(shown(parameter.baselineValue())));
            // † marks a value that is not the range minimum (low) or maximum (high) of a numeric parameter.
            space.parameter(key)
                    .filter(ParameterSpace.NumberParameter.class::isInstance)
                    .map(ParameterSpace.NumberParameter.class::cast)
                    .ifPresent(parameter -> {
                        assertThat(marks.get(key).get(0))
                                .as("† on low " + key)
                                .isEqualTo(
                                        new BigDecimal(variants.low().get(key).text()).compareTo(parameter.min()) != 0);
                        assertThat(marks.get(key).get(1))
                                .as("† on high " + key)
                                .isEqualTo(
                                        new BigDecimal(variants.high().get(key).text()).compareTo(parameter.max())
                                                != 0);
                    });
            assertThat(cells.get(1))
                    .as("low " + key)
                    .isEqualTo(shown(variants.low().get(key)));
            assertThat(cells.get(2))
                    .as("high " + key)
                    .isEqualTo(shown(variants.high().get(key)));
        });
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
