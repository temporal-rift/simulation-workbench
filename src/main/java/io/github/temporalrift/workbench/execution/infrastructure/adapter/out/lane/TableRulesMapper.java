package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Map;

import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;

/** Reads the paradox rules a case plays under from its effective rules; an absent key keeps the served default. */
final class TableRulesMapper {

    static final String CONTEST = "game.rules.paradox.detonate-contests-stabilize";
    static final String STABILIZED = "game.rules.scoring.score-deltas.PARADOX_STABILIZED";
    static final String DETONATED = "game.rules.scoring.score-deltas.PARADOX_DETONATED";
    static final String CASCADE = "game.rules.scoring.score-deltas.PARADOX_CASCADE_PENALTY";
    static final String TAPESTRY = "game.rules.tapestry-absorbs-chain-conflict";

    private TableRulesMapper() {}

    static TableRules of(CaseRules rules) {
        var properties = rules.properties();
        var defaults = TableRules.DEFAULTS;
        return new TableRules(
                bool(properties, CONTEST, defaults.detonateContestsStabilize()),
                integer(properties, STABILIZED, defaults.stabilizeDelta()),
                integer(properties, DETONATED, defaults.detonateDelta()),
                integer(properties, CASCADE, defaults.cascadePenalty()),
                bool(properties, TAPESTRY, defaults.tapestryAbsorbsChainConflict()));
    }

    private static boolean bool(Map<String, Object> properties, String key, boolean fallback) {
        var value = properties.get(key);
        return value == null ? fallback : Boolean.parseBoolean(value.toString());
    }

    private static int integer(Map<String, Object> properties, String key, int fallback) {
        var value = properties.get(key);
        return value == null ? fallback : (int) Math.round(Double.parseDouble(value.toString()));
    }
}
