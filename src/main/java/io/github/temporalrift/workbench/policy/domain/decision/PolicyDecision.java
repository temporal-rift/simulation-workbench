package io.github.temporalrift.workbench.policy.domain.decision;

import java.util.Objects;

/** The outcome of asking a policy to decide. */
public sealed interface PolicyDecision {

    /** A candidate was chosen. */
    record Chosen(Candidate candidate) implements PolicyDecision {
        public Chosen {
            Objects.requireNonNull(candidate, "candidate");
        }
    }

    /**
     * No candidate remains and no pass or decline is available. Distinct from pass and decline: the
     * policy never fabricates a legal choice.
     */
    record Exhausted() implements PolicyDecision {
        public static final String CODE = "POLICY_EXHAUSTED";
    }
}
