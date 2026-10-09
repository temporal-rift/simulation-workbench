package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.HandCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;

class ObservationJsonTest {

    @Test
    void theRetainedActionRoundObservationKeepsEveryHandCardWithItsPlayability() {
        var push = PolicyFixtures.card(1, CardType.PUSH);
        var trace = PolicyFixtures.card(2, CardType.TRACE);
        var window = new DecisionWindow.ActionRound(
                1,
                2,
                List.of(new HandCard(push, true), new HandCard(trace, false)),
                List.of(new PlayableCard(push, TargetShape.EVENT_OUTCOME, 1)),
                List.of());

        var retained = JsonMapper.builder()
                .build()
                .readTree(ObservationJson.of(PolicyFixtures.observation(Faction.ERASERS, window)));

        assertThat(retained.at("/window/hand"))
                .extracting(card -> card.at("/card/type").asString() + ":"
                        + card.at("/playable").asBoolean())
                .containsExactly("PUSH:true", "TRACE:false");
        assertThat(retained.at("/window/cards")).hasSize(1);
    }
}
