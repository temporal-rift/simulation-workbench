package io.github.temporalrift.workbench.policy.domain.observation;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;

class DecisionWindowTest {

    private static final DealtCard PUSH = PolicyFixtures.card(1, CardType.PUSH);
    private static final DealtCard TRACE = PolicyFixtures.card(2, CardType.TRACE);

    @Test
    void anActionRoundKeepsUnplayableCardsInTheHand() {
        assertThatNoException()
                .isThrownBy(() -> new DecisionWindow.ActionRound(
                        1,
                        1,
                        List.of(new HandCard(PUSH, true), new HandCard(TRACE, false)),
                        List.of(new PlayableCard(PUSH, TargetShape.EVENT_OUTCOME, 1)),
                        List.of()));
    }

    @Test
    void aPlayableCardOutsideTheHandIsRejected() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DecisionWindow.ActionRound(
                        1, 1, List.of(), List.of(new PlayableCard(PUSH, TargetShape.EVENT_OUTCOME, 1)), List.of()));
    }

    @Test
    void aCardTheHandMarksUnplayableCannotBeOffered() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new DecisionWindow.ActionRound(
                        1,
                        1,
                        List.of(new HandCard(TRACE, false)),
                        List.of(new PlayableCard(TRACE, TargetShape.PLAYER, 1)),
                        List.of()));
    }
}
