package io.github.temporalrift.workbench.policy.infrastructure.adapter.out.metrics;

import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.port.out.DecisionTimings;

/** Publishes {@code workbench.policy.decision}, tagged only with the policy id. */
public class MicrometerDecisionTimings implements DecisionTimings {

    private final MeterRegistry registry;

    public MicrometerDecisionTimings(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public PolicyDecision time(String policyId, Supplier<PolicyDecision> decision) {
        return registry.timer("workbench.policy.decision", "policy", policyId).record(decision);
    }
}
