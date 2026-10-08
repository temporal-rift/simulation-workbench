package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.CardGrade;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;

class CardRulesTest {

    @Test
    void everyCardTypeHasACategoryAndAShape() {
        Arrays.stream(CardType.values()).forEach(type -> {
            assertThat(CardRules.category(type)).isNotNull();
            assertThat(CardRules.shape(type)).isNotNull();
        });
    }

    @Test
    void shapesFollowThePublishedSubmissionContract() {
        assertThat(CardRules.shape(CardType.PUSH)).isEqualTo(TargetShape.EVENT_OUTCOME);
        assertThat(CardRules.shape(CardType.STALL)).isEqualTo(TargetShape.EVENT_OUTCOME);
        assertThat(CardRules.shape(CardType.TRACE)).isEqualTo(TargetShape.EVENT_OUTCOME);
        assertThat(CardRules.shape(CardType.SWING)).isEqualTo(TargetShape.OUTCOME_PAIR);
        assertThat(CardRules.shape(CardType.COLLIDE)).isEqualTo(TargetShape.OUTCOME_PAIR);
        assertThat(CardRules.shape(CardType.SCAN)).isEqualTo(TargetShape.EVENT_LIST);
        assertThat(CardRules.shape(CardType.NULLIFY)).isEqualTo(TargetShape.PLAYER_LIST);
        assertThat(CardRules.shape(CardType.DECOY)).isEqualTo(TargetShape.DISGUISE);
        for (var type : new CardType[] {CardType.REDIRECT, CardType.AMPLIFY, CardType.JAM, CardType.INTERCEPT}) {
            assertThat(CardRules.shape(type)).isEqualTo(TargetShape.PLAYER);
        }
    }

    @Test
    void listShapesNameAsManyTargetsAsTheirGradeAllows() {
        assertThat(CardRules.targetCount(CardType.SCAN, CardGrade.I)).isEqualTo(1);
        assertThat(CardRules.targetCount(CardType.SCAN, CardGrade.II)).isEqualTo(2);
        assertThat(CardRules.targetCount(CardType.SCAN, CardGrade.III)).isEqualTo(3);
        assertThat(CardRules.targetCount(CardType.NULLIFY, CardGrade.I)).isEqualTo(1);
        assertThat(CardRules.targetCount(CardType.NULLIFY, CardGrade.II)).isEqualTo(2);
        assertThat(CardRules.targetCount(CardType.PUSH, CardGrade.III)).isEqualTo(1);
    }

    @Test
    void categoriesFollowTheDealCategories() {
        assertThat(CardRules.category(CardType.AMPLIFY)).isEqualTo(CardCategory.PROBABILITY_SHIFTER);
        assertThat(CardRules.category(CardType.DECOY)).isEqualTo(CardCategory.INFORMATION);
        assertThat(CardRules.category(CardType.STALL)).isEqualTo(CardCategory.DISRUPTION);
        assertThat(CardRules.category(CardType.COLLIDE)).isEqualTo(CardCategory.PARADOX);
    }

    @Test
    void specialsNamePlayersOrEventOutcomesAndDeclarationsAreSeparate() {
        assertThat(CardRules.shape(SpecialAction.CORRUPT)).isEqualTo(TargetShape.PLAYER);
        assertThat(CardRules.shape(SpecialAction.EXPOSE)).isEqualTo(TargetShape.PLAYER);
        assertThat(CardRules.shape(SpecialAction.THREAD)).isEqualTo(TargetShape.EVENT_OUTCOME);
        assertThat(CardRules.isDeclarationOnly(SpecialAction.RALLY)).isTrue();
        assertThat(CardRules.isDeclarationOnly(SpecialAction.MOMENTUM)).isTrue();
        assertThat(CardRules.isDeclarationOnly(SpecialAction.EXPOSE)).isFalse();
    }
}
