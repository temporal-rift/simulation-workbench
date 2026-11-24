package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.Band;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionPhase;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionPopulation;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionTable;
import io.github.temporalrift.workbench.analysis.domain.criterion.IntervalRule;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

class ConfiguredBalanceCriteriaTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String GREEDY = "faction-greedy";
    private static final String SPREAD = "faction-spread";
    private static final String RANDOM = "random";
    private static final String MIXED = "random-seat-vs-faction-greedy";

    @Test
    void theConfigurationServesExactlyOneCriterion() {
        var criteria = new ConfiguredBalanceCriteria(MAPPER);

        assertThat(criteria.all()).hasSize(1);
        assertThat(criteria.all().getFirst().digest())
                .isEqualTo(TestCriteria.current().digest());
        var version = criteria.all().getFirst().version();
        assertThat(criteria.find(version)).isPresent();
        assertThat(criteria.find(version + 1)).isEmpty();
    }

    @Test
    void theAggregationAndEvidenceRuleHoldTheConfiguredValues() {
        var evidence = TestCriteria.current().evidence();

        assertThat(evidence.confidenceLevel()).isEqualTo(0.95);
        assertThat(evidence.executionFailedShareLimit()).isEqualTo(0.05);
        assertThat(evidence.invalidatingFailureCodes())
                .containsExactlyInAnyOrder(FailureCode.CONTRACT_MISMATCH, FailureCode.CONFIGURATION_DRIFT);
        assertThat(evidence.invalidatingEndReasons()).containsExactly(EndReason.ALL_PLAYERS_ABANDONED);
        assertThat(evidence.confirmationMinimumSeeds()).isEqualTo(104);
    }

    @Test
    void theDigestIsTheSha256OfTheCanonicalSerialisation() {
        var criterion = TestCriteria.current();
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
                new String[] {"\"confirmationMinimumSeeds\": 104", "\"confirmationMinimumSeeds\": 105"},
                new String[] {"\"GATE\"", "\"SCORED\""})) {
            var changed = source.replaceFirst(Pattern.quote(change[0]), change[1]);
            assertThat(changed).isNotEqualTo(source);
            assertThat(tryDigest(changed)).as(change[0]).isNotEqualTo(original);
        }
    }

    @Test
    void numbersAreHashedByValueSoTwoAndTwoPointZeroAreTheSameNumber() {
        var source = TestCriteria.source();
        var original = ConfiguredBalanceCriteria.parse(MAPPER, source).digest();

        var rewritten = source.replace("\"upper\": 2.0", "\"upper\": 2").replace("\"lower\": 0.5", "\"lower\": 0.50");

        assertThat(rewritten).isNotEqualTo(source);
        assertThat(ConfiguredBalanceCriteria.parse(MAPPER, rewritten).digest()).isEqualTo(original);
    }

    @Test
    void evidenceTheEvaluatorCannotApplyFailsAtLoadInsteadOfBeingServedWithOtherBehaviour() {
        var source = TestCriteria.source().replace("\r\n", "\n");

        for (var change : List.of(
                new String[] {"\"confidenceLevel\": 0.95", "\"confidenceLevel\": 0.9"},
                new String[] {"\"CONFIGURATION_DRIFT\"\n    ]", "\"EXECUTION_FAILED\"\n    ]"},
                new String[] {"\"ALL_PLAYERS_ABANDONED\"", "\"DECK_EXHAUSTED\""},
                new String[] {"WEIGHTED_SUM_OF_SQUARED_DEVIATIONS", "WORST_LINE"})) {
            var changed = source.replace(change[0], change[1]);
            assertThat(changed).as(change[0]).isNotEqualTo(source);
            assertThatThrownBy(() -> ConfiguredBalanceCriteria.parse(MAPPER, changed))
                    .as(change[1])
                    .isInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void tablesAndPopulationsNamePoliciesByIdWithoutAVersion() {
        var criterion = TestCriteria.current();

        assertThat(criterion.tables()).extracting(CriterionTable::id).containsExactly(GREEDY, SPREAD, RANDOM, MIXED);
        assertThat(criterion.tables())
                .flatExtracting(CriterionTable::seats)
                .allSatisfy(seat -> assertThat(seat).doesNotContainPattern("-v[0-9]"));
        assertThat(criterion.policies()).containsExactlyInAnyOrder(GREEDY, SPREAD, RANDOM);
    }

    @Test
    void theFactionSpreadTableIsFiveFactionSpreadSeatsInTheGreedyPhases() {
        var criterion = TestCriteria.current();

        var spread = criterion.table(SPREAD).orElseThrow();
        assertThat(spread.seats()).containsExactly(SPREAD, SPREAD, SPREAD, SPREAD, SPREAD);
        assertThat(spread.phases())
                .isEqualTo(criterion.table(GREEDY).orElseThrow().phases());
    }

    @Test
    void factionSpreadIsEvaluatedExactlyAsFactionGreedyOnTheLinesThatNameIt() {
        var criterion = TestCriteria.current();
        var shared = List.of("G-05", "G-06", "L-01", "L-02", "L-03", "L-04", "L-05", "L-06", "L-07");

        for (var line : criterion.lines()) {
            var tables =
                    line.populations().stream().map(CriterionPopulation::table).toList();
            if (shared.contains(line.id())) {
                assertThat(line.populations())
                        .as(line.id())
                        .containsExactly(
                                new CriterionPopulation(GREEDY, GREEDY), new CriterionPopulation(SPREAD, SPREAD));
            } else {
                assertThat(tables).as(line.id()).isNotEmpty().doesNotContain(SPREAD);
            }
        }
    }

    @Test
    void theRandomSeatPlaysAgainstFactionGreedySeatsAtConfirmationOnly() {
        var criterion = TestCriteria.current();

        var mixed = criterion.table(MIXED).orElseThrow();
        assertThat(mixed.seats()).containsExactly(RANDOM, GREEDY, GREEDY, GREEDY, GREEDY);
        assertThat(mixed.phases()).containsExactly(CriterionPhase.CONFIRMATION);
        assertThat(criterion.tables(CriterionPhase.SCREENING))
                .extracting(CriterionTable::id)
                .containsExactly(GREEDY, SPREAD, RANDOM);
        var g07 = line(criterion.lines(), "G-07");
        assertThat(g07.populations()).containsExactly(new CriterionPopulation(MIXED, RANDOM));
        assertThat(g07.band()).isEqualTo(new Band(null, 1.0, 1.0));
        assertThat(g07.role()).isEqualTo(CriterionRole.GATE);
        assertThat(g07.playerCounts()).containsExactly(3, 4, 5);
        for (var id : List.of("G-01", "G-02", "G-03")) {
            assertThat(line(criterion.lines(), id).populations())
                    .extracting(CriterionPopulation::table)
                    .containsExactly(GREEDY, RANDOM, MIXED);
        }
    }

    @Test
    void theExactIntervalIsUsedOnlyWhereTheCriterionNamesIt() {
        assertThat(TestCriteria.current().lines())
                .filteredOn(line -> line.interval() == IntervalRule.BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON)
                .extracting(CriterionLine::id)
                .containsExactly("G-01", "G-08", "L-03", "L-04", "L-05");
    }

    @Test
    void theExactBoundIsRefusedOnARatioAQuantileOrAMeanLine() {
        for (var id : List.of("G-05", "G-04", "L-02", "L-06", "L-07")) {
            var document = document();
            lineNode(document, id).put("interval", "BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON");
            var changed = MAPPER.writeValueAsString(document);

            assertThatThrownBy(() -> ConfiguredBalanceCriteria.parse(MAPPER, changed))
                    .as(id)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Clopper-Pearson");
        }
    }

    @Test
    void aPopulationMustNameAPolicyItsTablePlays() {
        var document = document();
        ((ObjectNode) lineNode(document, "G-04").get("populations").get(1)).put("policy", GREEDY);
        var changed = MAPPER.writeValueAsString(document);

        assertThatThrownBy(() -> ConfiguredBalanceCriteria.parse(MAPPER, changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no seat of table random plays");
    }

    private static ObjectNode document() {
        return (ObjectNode) MAPPER.readTree(TestCriteria.source());
    }

    private static ObjectNode lineNode(ObjectNode document, String id) {
        for (var line : document.get("lines")) {
            if (line.get("id").asString().equals(id)) {
                return (ObjectNode) line;
            }
        }
        throw new IllegalArgumentException(id);
    }

    private static CriterionLine line(List<CriterionLine> lines, String id) {
        return lines.stream().filter(line -> line.id().equals(id)).findFirst().orElseThrow();
    }

    private static String tryDigest(String source) {
        return ConfiguredBalanceCriteria.digestOf(MAPPER.readTree(source));
    }
}
