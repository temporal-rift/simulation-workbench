package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.Set;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/**
 * {@code random-v1}: a uniform draw over the canonically ordered, not-yet-excluded candidates of
 * the window, including the explicit pass or decline option.
 */
final class RandomPolicy implements BotPolicy {

    static final String DEFINITION = "uniform draw over canonically ordered candidates, pass and decline included";

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var available = CandidateGenerator.generate(observation).stream()
                .filter(candidate -> !excluded.contains(candidate))
                .toList();
        if (available.isEmpty()) {
            return new PolicyDecision.Exhausted();
        }
        return new PolicyDecision.Chosen(available.get(entropy.nextIndex(available.size())));
    }
}
