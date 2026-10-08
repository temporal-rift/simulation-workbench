package io.github.temporalrift.workbench.policy.application.command;

import java.util.Optional;

import io.github.temporalrift.workbench.policy.PolicyRuntime;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.domain.baseline.BaselinePolicies;
import io.github.temporalrift.workbench.policy.domain.baseline.PolicyBundle;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/** Serves the baseline bundles and binds the window procedure to a case's gateway. */
public class BaselinePolicyRuntime implements PolicyRuntime {

    @Override
    public Optional<BotPolicy> policy(String id, String version) {
        return BaselinePolicies.find(id, version).map(PolicyBundle::policy);
    }

    @Override
    public PlayDecisionWindowUseCase windowPlayer(ParticipantGateway gateway) {
        return new DecisionWindowService(gateway);
    }
}
