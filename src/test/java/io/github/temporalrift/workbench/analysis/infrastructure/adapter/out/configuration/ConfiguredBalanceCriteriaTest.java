package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.BalanceCriterion;
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
    private static final String GREEDY = "faction-greedy-v1";
    private static final String MIXED = "random-seat-vs-faction-greedy-v1";
    /** The digest version 1 was approved and served with; adding later versions must never change it. */
    private static final String VERSION_ONE_DIGEST = "1e2a1552bc125b4b28b14d5cb09adaf23036f5688fb8b46075965dae04cd8e22";

    @Test
    void theAggregationAndEvidenceRuleHoldTheVersionOneValues() {
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
    void theConfigurationServesVersionsOneAndTwoSoTheNewestIsTwo() {
        var criteria = new ConfiguredBalanceCriteria(MAPPER);

        assertThat(criteria.all()).extracting(BalanceCriterion::version).containsExactly(1, 2);
        assertThat(criteria.find(2))
                .map(BalanceCriterion::digest)
                .contains(TestCriteria.v2().digest());
    }

    @Test
    void versionOneUsesTheBootstrapOnEveryLineAndKeepsItsDigest() {
        var criterion = TestCriteria.v1();

        assertThat(criterion.lines())
                .allSatisfy(line -> assertThat(line.interval()).isEqualTo(IntervalRule.BLOCK_BOOTSTRAP));
        assertThat(criterion.digest()).isEqualTo(VERSION_ONE_DIGEST);
    }

    @Test
    void versionTwoPlaysTheRandomSeatAgainstFactionGreedySeatsAtConfirmationOnly() {
        var criterion = TestCriteria.v2();

        assertThat(criterion.version()).isEqualTo(2);
        assertThat(criterion.tables())
                .extracting(CriterionTable::id)
                .containsExactly("faction-greedy-v1", "random-v1", MIXED);
        var mixed = criterion.table(MIXED).orElseThrow();
        assertThat(mixed.seats()).containsExactly("random-v1", GREEDY, GREEDY, GREEDY, GREEDY);
        assertThat(mixed.phases()).containsExactly(CriterionPhase.CONFIRMATION);
        assertThat(criterion.tables(CriterionPhase.SCREENING))
                .extracting(CriterionTable::id)
                .containsExactly("faction-greedy-v1", "random-v1");
        assertThat(criterion.evidence().confirmationMinimumSeeds()).isEqualTo(104);
        var g07 = line(criterion.lines(), "G-07");
        assertThat(g07.populations()).containsExactly(new CriterionPopulation(MIXED, "random-v1"));
        assertThat(g07.band()).isEqualTo(new Band(null, 1.0, 1.0));
        assertThat(g07.role()).isEqualTo(CriterionRole.GATE);
        assertThat(g07.playerCounts()).containsExactly(3, 4, 5);
        for (var id : List.of("G-01", "G-02", "G-03")) {
            assertThat(line(criterion.lines(), id).populations())
                    .extracting(CriterionPopulation::table)
                    .containsExactly("faction-greedy-v1", "random-v1", MIXED);
        }
        assertThat(criterion.lines())
                .filteredOn(line -> line.interval() == IntervalRule.BLOCK_BOOTSTRAP_UNION_CLOPPER_PEARSON)
                .extracting(CriterionLine::id)
                .containsExactly("G-01", "G-08", "L-03", "L-04", "L-05");
    }

    @Test
    void versionTwoKeepsEveryOtherLineOfVersionOne() {
        var one = TestCriteria.v1().lines();
        var two = TestCriteria.v2().lines();

        for (var id : List.of("G-04", "G-05", "G-06", "G-08", "L-01", "L-02", "L-03", "L-04", "L-05", "L-06", "L-07")) {
            var before = line(one, id);
            var after = line(two, id);
            assertThat(after.populations()).as(id).isEqualTo(before.populations());
            assertThat(after.band()).as(id).isEqualTo(before.band());
            assertThat(after.role()).as(id).isEqualTo(before.role());
            assertThat(after.weight()).as(id).isEqualTo(before.weight());
            assertThat(after.playerCounts()).as(id).isEqualTo(before.playerCounts());
            assertThat(after.pooling()).as(id).isEqualTo(before.pooling());
        }
    }

    @Test
    void theExactBoundIsRefusedOnARatioOrAQuantileLine() {
        for (var id : List.of("G-05", "G-04")) {
            var document = versionTwo();
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
        var document = versionTwo();
        ((ObjectNode) lineNode(document, "G-04").get("populations").get(1)).put("policy", GREEDY);
        var changed = MAPPER.writeValueAsString(document);

        assertThatThrownBy(() -> ConfiguredBalanceCriteria.parse(MAPPER, changed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no seat of table random-v1 plays");
    }

    private static ObjectNode versionTwo() {
        return (ObjectNode) MAPPER.readTree(TestCriteria.v2Source());
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

    private static int factionsOf(CriterionLine line) {
        return line.dimension().eachFaction() ? 5 : 1;
    }
}
