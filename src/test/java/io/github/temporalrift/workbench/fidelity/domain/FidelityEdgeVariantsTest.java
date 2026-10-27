package io.github.temporalrift.workbench.fidelity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace.Parameter;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantMaterializer;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;

/** The edge variants validate against parameter space 1 and are exactly what the record tabulates. */
class FidelityEdgeVariantsTest {

    private static final Path RECORD = Path.of("docs", "fidelity", "v1.md");
    private static final String OFF_EDGE = "†";

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
    void everyOverrideIsAnEdgeOrIsMarkedInTheRecord() throws IOException {
        var parameters = new TreeMap<String, Parameter>();
        space.parameters().forEach(parameter -> parameters.put(parameter.key(), parameter));
        var table = table();
        assertEdges("low", variants.low(), parameters, Parameter::min, table);
        assertEdges("high", variants.high(), parameters, Parameter::max, table);
    }

    private static void assertEdges(
            String label,
            Map<String, BigDecimal> overrides,
            Map<String, Parameter> parameters,
            Function<Parameter, BigDecimal> edge,
            Map<String, Map<String, String>> table) {
        assertThat(overrides.keySet()).isEqualTo(parameters.keySet());
        overrides.forEach((key, value) -> {
            var offEdge = value.compareTo(edge.apply(parameters.get(key))) != 0;
            assertThat(table.get(key).get(label).endsWith(OFF_EDGE))
                    .as("%s %s is marked off the edge exactly when it is", label, key)
                    .isEqualTo(offEdge);
        });
    }

    @Test
    void theRecordTabulatesExactlyTheResource() throws IOException {
        var table = table();
        assertThat(table.keySet()).isEqualTo(variants.low().keySet());
        table.forEach((key, cells) -> {
            assertThat(new BigDecimal(cells.get("low").replace(OFF_EDGE, "").trim()))
                    .as("low %s", key)
                    .isEqualByComparingTo(variants.low().get(key));
            assertThat(new BigDecimal(cells.get("high").replace(OFF_EDGE, "").trim()))
                    .as("high %s", key)
                    .isEqualByComparingTo(variants.high().get(key));
        });
    }

    /** The override table of the record: key to its baseline, low and high cells. */
    private static Map<String, Map<String, String>> table() throws IOException {
        var rows = new TreeMap<String, Map<String, String>>();
        for (var line : Files.readAllLines(RECORD)) {
            if (!line.startsWith("| `game.rules.")) {
                continue;
            }
            var cells = line.substring(1, line.length() - 1).split("[|]");
            rows.put(
                    cells[0].replace("`", "").trim(),
                    Map.of("baseline", cells[1].trim(), "low", cells[2].trim(), "high", cells[3].trim()));
        }
        return rows;
    }
}
