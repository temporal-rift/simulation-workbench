package io.github.temporalrift.workbench.policy.domain.observation;

/**
 * The paradox rules a table plays under: public knowledge for every player, since the rules are announced before
 * the game. A policy weighs what clearing or tearing a paradox costs it with these values.
 *
 * @param detonateContestsStabilize whether Detonate outnumbering Stabilize lets a paradox cascade
 * @param stabilizeDelta the score delta a player's effective Stabilize records
 * @param detonateDelta the extra score delta each detonator records on a contested cascade
 * @param cascadePenalty the score delta every player records when an event cascades
 * @param tapestryAbsorbsChainConflict whether an armed Tapestry absorbs the Annihilate on its link outright
 */
public record TableRules(
        boolean detonateContestsStabilize,
        int stabilizeDelta,
        int detonateDelta,
        int cascadePenalty,
        boolean tapestryAbsorbsChainConflict) {

    /**
     * The values the configuration server serves, used when a case's rules leave a value out. The Stabilize delta is
     * deliberately 0 here, while the game's own fallback for an unconfigured deployment is -1: a policy plays the rules
     * the lanes serve, so this is not the place to align the two.
     */
    public static final TableRules DEFAULTS = new TableRules(true, 0, -1, -2, false);
}
