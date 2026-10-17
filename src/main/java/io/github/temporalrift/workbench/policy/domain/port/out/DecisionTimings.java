package io.github.temporalrift.workbench.policy.domain.port.out;

import java.util.function.Supplier;

import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;

/** Observes how long a policy takes to produce each decision. */
@FunctionalInterface
public interface DecisionTimings {

    /** Produces the decision and records its duration under the policy's id. */
    PolicyDecision time(String policyId, Supplier<PolicyDecision> decision);

    static DecisionTimings untimed() {
        return (_, decision) -> decision.get();
    }
}
