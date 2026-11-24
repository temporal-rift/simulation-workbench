package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;

/**
 * {@code faction-greedy}: the paradox layer that first contests a Weaver chain on its way to completion
 * ({@link ChainContest}) and never proposes a special the table reports unavailable in the open round, so it does not
 * spend attempts on specials the game would refuse for the era, the round, a budget or a jam. The report is advisory:
 * a special refused for its target or a prerequisite is still excluded and retried.
 */
final class FactionGreedyPolicy implements BotPolicy {

    private final ParadoxAwarePolicy recipe = new ParadoxAwarePolicy();

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        if (!(observation.window() instanceof DecisionWindow.ActionRound round)) {
            return recipe.decide(observation, excluded, entropy);
        }
        var contest = ChainContest.choose(observation, excluded);
        if (contest.isPresent()) {
            return new PolicyDecision.Chosen(contest.get());
        }
        return recipe.decide(observation, withoutUnavailable(observation, round, excluded), entropy);
    }

    /** The excluded set with every play of a special the table reports unavailable this round added to it. */
    static Set<Candidate> withoutUnavailable(
            EntitledObservation observation, DecisionWindow.ActionRound round, Set<Candidate> excluded) {
        var unavailable = round.specials().stream()
                .filter(PlayableSpecial::unavailable)
                .map(PlayableSpecial::action)
                .collect(Collectors.toSet());
        if (unavailable.isEmpty()) {
            return excluded;
        }
        var withoutUnavailable = new HashSet<>(excluded);
        CandidateGenerator.generate(observation).stream()
                .filter(candidate ->
                        candidate instanceof Candidate.PlaySpecial special && unavailable.contains(special.action()))
                .forEach(withoutUnavailable::add);
        return withoutUnavailable;
    }
}
