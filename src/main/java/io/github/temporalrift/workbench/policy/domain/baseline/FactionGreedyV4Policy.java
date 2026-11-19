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
 * {@code faction-greedy-v4}: {@code faction-greedy-v3} that never proposes a special the table reports unavailable in
 * the open round, so it does not spend attempts on specials the game would refuse for the era, the round, a budget or
 * a jam. The report is advisory: a special refused for its target or a prerequisite is still excluded and retried.
 */
final class FactionGreedyV4Policy implements BotPolicy {

    static final String DEFINITION = """
            faction-greedy v4: faction-greedy v3 without the specials the table reports unavailable this round: every \
            play of a special that the observation marks unavailable is excluded before v3 decides
            """ + FactionGreedyV3Policy.DEFINITION;

    private final FactionGreedyV3Policy recipe = new FactionGreedyV3Policy();

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        if (!(observation.window() instanceof DecisionWindow.ActionRound round)) {
            return recipe.decide(observation, excluded, entropy);
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
