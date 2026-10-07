package io.github.temporalrift.workbench.policy.domain.decision;

import java.util.Set;

import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/** A deterministic decision procedure over a frozen entitled observation. */
public interface BotPolicy {

    /**
     * Chooses among the canonically ordered candidates of the observation that are not excluded.
     * Returns {@link PolicyDecision.Exhausted} when none remains.
     */
    PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy);
}
