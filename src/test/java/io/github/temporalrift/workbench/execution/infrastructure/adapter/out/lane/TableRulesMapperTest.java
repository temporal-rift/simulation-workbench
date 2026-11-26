package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;

class TableRulesMapperTest {

    private static CaseRules rules(Map<String, Object> properties) {
        return new CaseRules(new TreeMap<>(properties), "a".repeat(64));
    }

    @Test
    void theFiveRulesAreReadFromTheirServedKeys() {
        var rules = TableRulesMapper.of(rules(Map.of(
                "game.rules.paradox.detonate-contests-stabilize", false,
                "game.rules.scoring.score-deltas.PARADOX_STABILIZED", -1,
                "game.rules.scoring.score-deltas.PARADOX_DETONATED", -3,
                "game.rules.scoring.score-deltas.PARADOX_CASCADE_PENALTY", -4,
                "game.rules.tapestry-absorbs-chain-conflict", true)));

        assertThat(rules).isEqualTo(new TableRules(false, -1, -3, -4, true));
    }

    @Test
    void theChainLengthAndTheCollapseLimitAreReadWhenTheCaseSaysThem() {
        var rules = TableRulesMapper.of(rules(Map.of(
                "game.rules.victory.weaver-chain-length",
                5,
                "game.rules.max-cascaded-paradoxes",
                new BigDecimal("4.0"))));

        assertThat(rules.chainLength()).isEqualTo(5);
        assertThat(rules.collapseLimit()).isEqualTo(4);
    }

    @Test
    void aCaseThatLeavesThemOutReadsTheParameterSpaceBaselines() {
        var rules = TableRulesMapper.of(rules(Map.of()));

        assertThat(rules.weaverChainLength()).isNull();
        assertThat(rules.maxCascadedParadoxes()).isNull();
        assertThat(rules.chainLength()).isEqualTo(4);
        assertThat(rules.collapseLimit()).isEqualTo(3);
    }

    @Test
    void numbersAreReadWhateverTheirJsonType() {
        var rules = TableRulesMapper.of(rules(Map.of(
                "game.rules.scoring.score-deltas.PARADOX_DETONATED",
                new BigDecimal("-2.0"),
                "game.rules.scoring.score-deltas.PARADOX_CASCADE_PENALTY",
                -3.0)));

        assertThat(rules.detonateDelta()).isEqualTo(-2);
        assertThat(rules.cascadePenalty()).isEqualTo(-3);
    }

    @Test
    void aRuleTheCaseLeavesOutKeepsTheServedDefault() {
        var rules = TableRulesMapper.of(rules(Map.of("game.rules.scoring.score-deltas.PARADOX_STABILIZED", -1)));

        assertThat(rules.stabilizeDelta()).isEqualTo(-1);
        assertThat(rules.detonateContestsStabilize()).isEqualTo(TableRules.DEFAULTS.detonateContestsStabilize());
        assertThat(rules.detonateDelta()).isEqualTo(TableRules.DEFAULTS.detonateDelta());
        assertThat(rules.cascadePenalty()).isEqualTo(TableRules.DEFAULTS.cascadePenalty());
        assertThat(rules.tapestryAbsorbsChainConflict()).isEqualTo(TableRules.DEFAULTS.tapestryAbsorbsChainConflict());
    }

    @Test
    void aCaseWithoutAnyOfTheKeysPlaysTheServedDefaults() {
        assertThat(TableRulesMapper.of(rules(Map.of()))).isEqualTo(TableRules.DEFAULTS);
    }

    @Test
    void theKeysAreTheOnesTheServicesBind() {
        assertThat(TableRulesMapper.CONTEST).isEqualTo("game.rules.paradox.detonate-contests-stabilize");
        assertThat(TableRulesMapper.STABILIZED).isEqualTo("game.rules.scoring.score-deltas.PARADOX_STABILIZED");
        assertThat(TableRulesMapper.DETONATED).isEqualTo("game.rules.scoring.score-deltas.PARADOX_DETONATED");
        assertThat(TableRulesMapper.CASCADE).isEqualTo("game.rules.scoring.score-deltas.PARADOX_CASCADE_PENALTY");
        assertThat(TableRulesMapper.TAPESTRY).isEqualTo("game.rules.tapestry-absorbs-chain-conflict");
    }
}
