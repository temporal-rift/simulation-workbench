package io.github.temporalrift.workbench.experiment.domain.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Constraint;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.HeldFixed;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;

/** Parameter space version 1 as served equals the document that defines it, and its baselines are the lanes'. */
class ParameterSpaceVersion1Test {

    private static final Path DOCUMENT = Path.of("docs", "parameter-space", "v1.md");
    private static final String SCORE_DELTAS = "game.rules.scoring.score-deltas.";

    private final ParameterSpace space = VariantRulesFixtures.spaces().find(1).orElseThrow();

    /** The rows of each table of the document, by the heading above it. */
    private static Map<String, List<List<String>>> tables() throws IOException {
        var tables = new LinkedHashMap<String, List<List<String>>>();
        List<List<String>> current = null;
        for (var line : Files.readAllLines(DOCUMENT)) {
            if (line.startsWith("#")) {
                current = new ArrayList<>();
                tables.put(line.replaceAll("^#+ ", ""), current);
            } else if (current != null && line.startsWith("|") && !line.matches("^\\|[-| ]+\\|$")) {
                var cells = Arrays.stream(line.substring(1, line.length() - 1).split("\\|"))
                        .map(cell -> cell.replace("`", "").replace('−', '-').trim())
                        .toList();
                current.add(cells);
            }
        }
        tables.values().forEach(rows -> {
            if (!rows.isEmpty()) {
                rows.removeFirst();
            }
        });
        return tables;
    }

    private static List<BigDecimal> range(String cell) {
        return Arrays.stream(cell.replaceAll("[\\[\\]]", "").split(","))
                .map(value -> new BigDecimal(value.trim()))
                .toList();
    }

    private static Parameter parameter(String key, String services, String baseline, String range, String step) {
        var bounds = range(range);
        return new Parameter(
                key, List.of(services), new BigDecimal(baseline), bounds.get(0), bounds.get(1), new BigDecimal(step));
    }

    private static boolean sameParameter(Parameter a, Parameter b) {
        return a.key().equals(b.key())
                && a.services().equals(b.services())
                && a.baseline().compareTo(b.baseline()) == 0
                && a.min().compareTo(b.min()) == 0
                && a.max().compareTo(b.max()) == 0
                && a.step().compareTo(b.step()) == 0;
    }

    @Test
    void itsParametersAreTheOnesTheDocumentTabulates() throws IOException {
        var tables = tables();
        var expected = new ArrayList<Parameter>();
        for (var section : List.of("Victory", "Special endings", "Hand and deal", "Probability effects")) {
            tables.get(section)
                    .forEach(
                            row -> expected.add(parameter(row.get(0), row.get(1), row.get(2), row.get(5), row.get(6))));
        }
        tables.get("Scoring")
                .forEach(row -> expected.add(
                        parameter(SCORE_DELTAS + row.get(0), "game-service", row.get(1), row.get(4), row.get(5))));

        assertThat(expected).hasSize(46);
        assertThat(space.parameters()).hasSameSizeAs(expected);
        for (int i = 0; i < expected.size(); i++) {
            assertThat(sameParameter(space.parameters().get(i), expected.get(i)))
                    .as(expected.get(i).key())
                    .isTrue();
        }
    }

    @Test
    void itsConstraintsAreTheOnesTheDocumentTabulates() throws IOException {
        var rows = tables().get("Constraints");

        assertThat(space.constraints())
                .extracting(Constraint::id, Constraint::description)
                .containsExactlyElementsOf(rows.stream()
                        .map(row -> org.assertj.core.groups.Tuple.tuple(row.get(0), row.get(1)))
                        .toList());
    }

    @Test
    void itsHeldFixedPropertiesAreTheOnesTheDocumentListsWithTheirReasons() throws IOException {
        var rows = tables().get("Held fixed");

        var expected = new ArrayList<HeldFixed>();
        for (var row : rows) {
            var keys = Arrays.asList(row.get(0).split("\\s*,\\s*"));
            if (keys.stream().allMatch(key -> key.startsWith("game.rules."))) {
                keys.forEach(key -> expected.add(new HeldFixed(key, row.get(2))));
            } else {
                expected.add(new HeldFixed(row.get(0), row.get(2)));
            }
        }
        assertThat(space.heldFixed()).containsExactlyElementsOf(expected);
    }

    @Test
    void itsDerivedKeysMirrorTheirSources() {
        assertThat(space.derived())
                .extracting(
                        ParameterSpace.Derived::key,
                        ParameterSpace.Derived::from,
                        d -> d.factor().intValue())
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(
                                "game.rules.probability.suppress-shift.I", "game.rules.probability.push-shift.I", -1),
                        org.assertj.core.groups.Tuple.tuple(
                                "game.rules.probability.suppress-shift.II", "game.rules.probability.push-shift.II", -1),
                        org.assertj.core.groups.Tuple.tuple(
                                "game.rules.probability.suppress-shift.III",
                                "game.rules.probability.push-shift.III",
                                -1),
                        org.assertj.core.groups.Tuple.tuple(
                                SCORE_DELTAS + "EXPOSE_CHANGED_PLAYER_BEHAVIOR",
                                SCORE_DELTAS + "EXPOSE_SIGNATURE_REVEALED",
                                1));
    }

    @Test
    void everyParametersBaselineIsTheValueTheLanesServe() {
        var baseline = VariantRulesFixtures.baseline();

        space.parameters()
                .forEach(parameter -> assertThat(baseline.number(parameter.key()))
                        .as(parameter.key())
                        .hasValueSatisfying(value -> assertThat(value).isEqualByComparingTo(parameter.baseline())));
    }

    @Test
    void everyDerivedValueOfTheBaselineIsItsFactorTimesItsSource() {
        var baseline = VariantRulesFixtures.baseline();

        space.derived().stream()
                .filter(derived -> baseline.number(derived.key()).isPresent())
                .forEach(derived -> assertThat(baseline.number(derived.key()).orElseThrow())
                        .as(derived.key())
                        .isEqualByComparingTo(
                                baseline.number(derived.from()).orElseThrow().multiply(derived.factor())));
    }

    @Test
    void everyConstraintHoldsOnTheBaselineAndReadsOnlyServedKeys() {
        var baseline = VariantRulesFixtures.baseline();

        space.constraints().forEach(constraint -> {
            assertThat(constraint.expression().holds(baseline))
                    .as(constraint.id())
                    .isTrue();
            assertThat(constraint.expression().keys().stream()
                            .filter(key -> baseline.number(key).isEmpty())
                            .toList())
                    .as(constraint.id())
                    .isEmpty();
        });
    }

    @Test
    void noHeldFixedPropertyIsAlsoAParameter() {
        var parameters = space.parameters().stream().map(Parameter::key).toList();

        assertThat(space.heldFixed()).extracting(HeldFixed::key).doesNotContainAnyElementsOf(parameters);
    }
}
