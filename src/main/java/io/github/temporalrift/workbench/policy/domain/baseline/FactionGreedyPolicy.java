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

    static final String DEFINITION = """
            faction-greedy: the paradox layer without the specials the table reports unavailable this round: every \
            play of a special that the observation marks unavailable is excluded before the paradox layer decides
            chain contest, in an action round before anything else: a seat that is not a Weaver, facing an ACTIVE chain \
            of at least 2 confirmed links with a visible pending link that is not its own commitment, plays ANNIHILATE \
            on the pending event and outcome when it is available, else its highest-grade SUPPRESS targeting it
            """ + ParadoxAwarePolicy.DEFINITION;

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
        var unavailable = round.specials().stream()
                .filter(PlayableSpecial::unavailable)
                .map(PlayableSpecial::action)
                .collect(Collectors.toSet());
        if (unavailable.isEmpty()) {
            return recipe.decide(observation, excluded, entropy);
        }
        var withoutUnavailable = new HashSet<>(excluded);
        CandidateGenerator.generate(observation).stream()
                .filter(candidate ->
                        candidate instanceof Candidate.PlaySpecial special && unavailable.contains(special.action()))
                .forEach(withoutUnavailable::add);
        return recipe.decide(observation, withoutUnavailable, entropy);
    }
}
