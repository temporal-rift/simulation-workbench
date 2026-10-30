package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.TestCriteria;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionLine;
import io.github.temporalrift.workbench.analysis.domain.criterion.CriterionRole;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;

class ConfiguredBalanceCriteriaTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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

    private static String tryDigest(String source) {
        return ConfiguredBalanceCriteria.digestOf(MAPPER.readTree(source));
    }

    private static int factionsOf(CriterionLine line) {
        return line.dimension().eachFaction() ? 5 : 1;
    }
}
