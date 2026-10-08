package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.policy;

import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.policy.PolicyRuntime;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/** Reaches the policy module's public API. */
public class DecisionRuntimeAdapter implements DecisionRuntime {

    private final PolicyRuntime runtime;

    public DecisionRuntimeAdapter(PolicyRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public Optional<BotPolicy> policy(String id, String version) {
        return runtime.policy(id, version);
    }

    @Override
    public PlayDecisionWindowUseCase windowPlayer(ParticipantGateway gateway) {
        return runtime.windowPlayer(gateway);
    }
}
