package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * {@code faction-spread}: {@code faction-greedy} (the public-chain contest, the unavailable-special filter, the
 * paradox layer and the recipe) with its bets spread over the era's events instead of piled on the top-ranked outcome.
 *
 * <p>Its differences from {@code faction-greedy}: each bet's event is a seeded draw among the era's live events,
 * weighted by their favorites' known weight; the Prophet names the event's runner-up half the time; the Eraser
 * annihilates a drawn favorite; a seat other than the Weaver does not push or swing toward the public pending link; and
 * a seat plays Detonate to break a leading chain or, when it leads, to finish a collapse. See {@link SpreadLayer} and
 * {@link ParadoxAwarePolicy}.
 */
final class FactionSpreadPolicy implements BotPolicy {

    private final Map<Faction, SearchParameters> params = new EnumMap<>(Faction.class);
    private final Map<Faction, ParadoxAwarePolicy> recipes = new EnumMap<>(Faction.class);

    FactionSpreadPolicy() {
        this(SearchParameters.DEFAULTS);
    }

    /**
     * {@code faction-spread} with the preferences, thresholds and behaviors the parameters name. The thresholds and
     * behaviors are those of the faction the seat plays, so a parameter set changes the play of its own faction only.
     */
    FactionSpreadPolicy(SearchParameters parameters) {
        for (var faction : Faction.values()) {
            var scoped = parameters.forFaction(faction);
            params.put(faction, scoped);
            recipes.put(faction, new ParadoxAwarePolicy(true, scoped));
        }
    }

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var scoped = params.get(observation.faction());
        var recipe = recipes.get(observation.faction());
        var available = excluded;
        if (observation.window() instanceof DecisionWindow.ActionRound round) {
            var contest = ChainContest.choose(observation, excluded, scoped);
            if (contest.isPresent()) {
                return new PolicyDecision.Chosen(contest.get());
            }
            available = FactionGreedyPolicy.withoutUnavailable(observation, round, excluded);
        }
        return recipe.decide(
                observation, available, SpreadLayer.setAside(observation, available, entropy, scoped), entropy);
    }
}
