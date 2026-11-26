package io.github.temporalrift.workbench.execution.domain.port.out;

import java.util.Optional;

import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/** The bot policies and the decision-window procedure that play a case's seats. */
public interface DecisionRuntime {

    Optional<BotPolicy> policy(String id, String version);

    /** The policy of an entry that names parameters; a bundle that declares none ignores them. */
    default Optional<BotPolicy> policy(String id, String version, java.util.Map<String, Integer> parameters) {
        return policy(id, version);
    }

    /** The window procedure bound to one case's participant gateway. */
    PlayDecisionWindowUseCase windowPlayer(ParticipantGateway gateway);
}
