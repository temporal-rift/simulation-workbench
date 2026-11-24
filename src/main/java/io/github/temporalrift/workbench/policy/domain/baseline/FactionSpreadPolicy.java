package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.Set;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/**
 * {@code faction-spread}: {@code faction-greedy} (the public-chain contest, the unavailable-special filter, the
 * paradox layer and the recipe) with its bets spread over the era's events instead of piled on the top-ranked outcome.
 * The definition names every difference, so its digest is distinct.
 */
final class FactionSpreadPolicy implements BotPolicy {

    static final String DEFINITION = """
            faction-spread: faction-greedy with its bets spread over the era's events
            event favorite: within one event, the outcome with the highest public band (none = MEDIUM), then own Scan \
            value else printed weight; a tie is drawn
            spread bets: for Thread, Rewrite, Foresight, Reweave and the Rally/Momentum declaration the target event \
            is drawn among the era's live events (not stalled; for Thread and Reweave also not the event of a \
            confirmed chain link; the action still open and not refused or wasted), each weighted by its favorite's \
            known weight (uniform when all weigh 0), and the bet names that event's favorite
            prophet runner-up: on the drawn event, Foresight names the favorite with probability 1/2, else the \
            event's second outcome
            eraser target: Annihilate targets a draw among event favorites weighted by known weight; the public-chain \
            contest still comes first
            public link: a seat that is not a Weaver scores a Push or Swing toward the public pending link's outcome \
            200 lower unless it is its own commitment
            chain break: in paradox resolution a seat that is not a Weaver plays Detonate on a Chain Conflict when the \
            ACTIVE public chain has at least weaver-chain-length - 2 confirmed links; a seat that leads the public \
            scores (no other seat higher) plays Detonate on an open paradox not yet cascaded when the distinct \
            cascaded events equal max-cascaded-paradoxes - 1; otherwise the paradox layer decides
            """ + FactionGreedyPolicy.DEFINITION;

    private final ParadoxAwarePolicy recipe = new ParadoxAwarePolicy(true);

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var available = excluded;
        if (observation.window() instanceof DecisionWindow.ActionRound round) {
            var contest = ChainContest.choose(observation, excluded);
            if (contest.isPresent()) {
                return new PolicyDecision.Chosen(contest.get());
            }
            available = FactionGreedyPolicy.withoutUnavailable(observation, round, excluded);
        }
        return recipe.decide(observation, available, SpreadLayer.setAside(observation, available, entropy), entropy);
    }
}
