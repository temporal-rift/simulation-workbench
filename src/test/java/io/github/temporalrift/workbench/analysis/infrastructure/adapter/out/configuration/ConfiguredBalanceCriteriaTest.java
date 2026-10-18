package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.Band;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.criterion.Derivation;
import io.github.temporalrift.workbench.analysis.domain.criterion.Pooling;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.FailureCode;

class ConfiguredBalanceCriteriaTest {

    private static final Path DESIGN_RECORD = Path.of("docs", "balance-criteria", "v1.md");
    private static final Pattern LINE_ROW = Pattern.compile("^\\| ([GL]-\\d\\d) \\|.*");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`]*)`");
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    @Test
    void theConfiguredCriterionEqualsTheDesignRecordLineByLine() throws IOException {
        var designed = designedLines();
        var configured = TestCriteria.v1().lines();

        assertThat(designed).hasSize(15);
        assertThat(configured).extracting(CriterionLine::id).containsExactlyElementsOf(ids(designed));
        for (var index = 0; index < designed.size(); index++) {
            var row = designed.get(index);
            var line = configured.get(index);
            var id = row.get(0);
            assertThat(line.metric()).as(id + " metric").isEqualTo(metricOf(row.get(1)));
            assertThat(line.derivation()).as(id + " derivation").isEqualTo(derivationOf(row.get(1), row.get(2)));
            assertDimension(id, line, row.get(2));
            assertThat(line.policies()).as(id + " policies").isEqualTo(policiesOf(row.get(3)));
            assertThat(line.playerCounts()).as(id + " players").isEqualTo(playersOf(row.get(4)));
            assertThat(line.pooling())
                    .as(id + " pooling")
                    .isEqualTo(row.get(5).equals("Pooled") ? Pooling.POOLED : Pooling.PER_FACTION_SET);
            assertThat(line.band()).as(id + " band").isEqualTo(bandOf(row.get(6)));
            assertThat(line.role()).as(id + " role").isEqualTo(CriterionRole.valueOf(row.get(7)));
            assertThat(line.weight()).as(id + " weight").isEqualTo(weightOf(row.get(8)));
            assertThat(line.rationale()).as(id + " rationale").isEqualTo(row.get(9));
        }
    }

    @Test
    void theAggregationAndEvidenceRuleMatchTheDesignRecord() {
        var evidence = TestCriteria.v1().evidence();

        assertThat(evidence.confidenceLevel()).isEqualTo(0.95);
        assertThat(evidence.executionFailedShareLimit()).isEqualTo(0.05);
        assertThat(evidence.invalidatingFailureCodes())
                .containsExactlyInAnyOrder(FailureCode.CONTRACT_MISMATCH, FailureCode.CONFIGURATION_DRIFT);
        assertThat(evidence.invalidatingEndReasons()).containsExactly(EndReason.ALL_PLAYERS_ABANDONED);
        assertThat(evidence.confirmationMinimumSeeds()).isEqualTo(100);
        var weights = TestCriteria.v1().lines().stream()
                .filter(line -> line.role() == CriterionRole.SCORED)
                .mapToDouble(line -> line.weight() * line.playerCounts().size() * factionsOf(line))
                .sum();
        assertThat(weights).isCloseTo(4 + 2 + 1 + 1 + 1 + 1 + 1, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void theDigestIsTheSha256OfTheCanonicalSerialisation() {
        var criterion = TestCriteria.v1();
        var source = TestCriteria.source();

        assertThat(criterion.digest()).matches("[a-f0-9]{64}");
        assertThat(ConfiguredBalanceCriteria.parse(MAPPER, source).digest()).isEqualTo(criterion.digest());
        var reformatted = MAPPER.writeValueAsString(MAPPER.readTree(source));
        assertThat(ConfiguredBalanceCriteria.parse(MAPPER, reformatted).digest())
                .as("whitespace does not change the digest")
                .isEqualTo(criterion.digest());
    }

    @Test
    void changingAnyValueChangesTheDigest() {
        var source = TestCriteria.source();
        var original = ConfiguredBalanceCriteria.parse(MAPPER, source).digest();

        for (var change : List.of(
                new String[] {"\"upper\": 0.02", "\"upper\": 0.03"},
                new String[] {"\"confirmationMinimumSeeds\": 100", "\"confirmationMinimumSeeds\": 101"},
                new String[] {"Balance criterion version 1", "Balance criterion version 1 "},
                new String[] {"\"GATE\"", "\"SCORED\""})) {
            var changed = source.replaceFirst(Pattern.quote(change[0]), change[1]);
            assertThat(changed).isNotEqualTo(source);
            var digest = tryDigest(changed);
            assertThat(digest).as(change[0]).isNotEqualTo(original);
        }
    }

    private static String tryDigest(String source) {
        return ConfiguredBalanceCriteria.digestOf(MAPPER.readTree(source));
    }

    private static int factionsOf(CriterionLine line) {
        return line.dimension().eachFaction() ? 5 : 1;
    }

    private static void assertDimension(String id, CriterionLine line, String cell) {
        var dimension = line.dimension();
        var text = cell.replace("`", "");
        if (text.startsWith("sum of ")) {
            assertThat(dimension.failureCodes())
                    .as(id + " codes")
                    .containsExactlyElementsOf(
                            Arrays.stream(text.substring("sum of ".length()).split(" and "))
                                    .map(FailureCode::valueOf)
                                    .toList());
        } else if (text.startsWith("quantile ")) {
            assertThat(dimension.quantile()).as(id + " quantile").isEqualTo(Double.parseDouble(text.substring(9)));
        } else if (text.equals("each faction")) {
            assertThat(dimension.eachFaction()).as(id + " each faction").isTrue();
        } else if (text.matches("[A-Z_]+")) {
            assertThat(dimension.endReason()).as(id + " end reason").isEqualTo(EndReason.valueOf(text));
        } else {
            assertThat(text).as(id + " unsplit cell").isIn("", "none", "mean");
            assertThat(dimension.eachFaction()).isFalse();
            assertThat(dimension.endReason()).isNull();
            assertThat(dimension.quantile()).isNull();
            assertThat(dimension.failureCodes()).isNull();
        }
    }

    private static String metricOf(String cell) {
        var metric = BACKTICKED.matcher(cell);
        assertThat(metric.find()).isTrue();
        return metric.group(1);
    }

    private static Derivation derivationOf(String metricCell, String dimensionCell) {
        if (metricCell.contains("÷")) {
            return Derivation.RATIO_TO_WIN_REFERENCE;
        }
        return dimensionCell.startsWith("sum of") ? Derivation.SUM_OF_FAILURE_CODES : Derivation.DIRECT;
    }

    private static List<String> policiesOf(String cell) {
        return Arrays.stream(cell.split(","))
                .map(String::trim)
                .map(policy -> policy.equals("fg") ? "faction-greedy-v1" : "random-v1")
                .toList();
    }

    private static List<Integer> playersOf(String cell) {
        return cell.equals("All")
                ? List.of(3, 4, 5)
                : Arrays.stream(cell.split(","))
                        .map(String::trim)
                        .map(Integer::parseInt)
                        .toList();
    }

    private static Band bandOf(String cell) {
        var limit = BACKTICKED.matcher(cell);
        assertThat(limit.find()).isTrue();
        var text = limit.group(1);
        var scale = cell.contains("s = ") ? Double.parseDouble(cell.substring(cell.indexOf("s = ") + 4)) : null;
        if (text.startsWith("[")) {
            var bounds = text.substring(1, text.length() - 1).split(",");
            return new Band(Double.parseDouble(bounds[0].trim()), Double.parseDouble(bounds[1].trim()), null);
        }
        var value = Double.parseDouble(text.substring(2).trim());
        return text.startsWith("≤") ? new Band(null, value, scale) : new Band(value, null, scale);
    }

    private static Double weightOf(String cell) {
        if (cell.isBlank()) {
            return null;
        }
        var fraction = cell.replace(" per line", "").split("/");
        return Double.parseDouble(fraction[0]) / Double.parseDouble(fraction[1]);
    }

    private static List<String> ids(List<List<String>> rows) {
        return rows.stream().map(row -> row.get(0)).toList();
    }

    private static List<List<String>> designedLines() throws IOException {
        var rows = new ArrayList<List<String>>();
        for (var text : Files.readAllLines(DESIGN_RECORD, StandardCharsets.UTF_8)) {
            if (LINE_ROW.matcher(text).matches()) {
                var cells = Arrays.stream(text.substring(1, text.length() - 1).split("\\|", -1))
                        .map(String::trim)
                        .toList();
                rows.add(cells);
            }
        }
        return rows;
    }
}
