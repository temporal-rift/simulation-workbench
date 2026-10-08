package io.github.temporalrift.workbench.policy;

import java.util.Optional;

import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/** Public API of the policy module: the defined policies and the decision-window procedure that runs them. */
public interface PolicyRuntime {

    /** The defined policy for a manifest reference, if any. */
    Optional<BotPolicy> policy(String id, String version);

    /** The window procedure bound to one case's participant gateway. */
    PlayDecisionWindowUseCase windowPlayer(ParticipantGateway gateway);
}
