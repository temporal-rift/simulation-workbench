package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.util.Set;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.CardGrade;
import io.github.temporalrift.workbench.policy.domain.observation.CardType;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;

/**
 * The structural facts of the published submission contract that the participant projection does not
 * carry: a card type's public category and the shape of target its submission requires. They are fixed
 * contract facts, never balance values.
 */
final class CardRules {

    private static final Set<CardType> PLAYER_TARGETING =
            Set.of(CardType.REDIRECT, CardType.AMPLIFY, CardType.JAM, CardType.INTERCEPT);

    private CardRules() {}

    static CardCategory category(CardType type) {
        return switch (type) {
            case PUSH, SUPPRESS, SWING, AMPLIFY -> CardCategory.PROBABILITY_SHIFTER;
            case INTERCEPT, SCAN, TRACE, DECOY -> CardCategory.INFORMATION;
            case JAM, STALL, REDIRECT, NULLIFY -> CardCategory.DISRUPTION;
            case COLLIDE, STABILIZE, DETONATE -> CardCategory.PARADOX;
        };
    }

    static TargetShape shape(CardType type) {
        if (type == CardType.DECOY) {
            return TargetShape.DISGUISE;
        }
        if (PLAYER_TARGETING.contains(type)) {
            return TargetShape.PLAYER;
        }
        return switch (type) {
            case NULLIFY -> TargetShape.PLAYER_LIST;
            case SWING, COLLIDE -> TargetShape.OUTCOME_PAIR;
            case SCAN -> TargetShape.EVENT_LIST;
            default -> TargetShape.EVENT_OUTCOME;
        };
    }

    /** How many events or participants a list-shaped submission names at the grade; 1 for every other shape. */
    static int targetCount(CardType type, CardGrade grade) {
        return switch (type) {
            case SCAN -> grade.ordinal() + 1;
            case NULLIFY -> grade == CardGrade.I ? 1 : 2;
            default -> 1;
        };
    }

    /** Corrupt and Expose name a participant; every other special names an event and one of its outcomes. */
    static TargetShape shape(SpecialAction action) {
        return action == SpecialAction.CORRUPT || action == SpecialAction.EXPOSE
                ? TargetShape.PLAYER
                : TargetShape.EVENT_OUTCOME;
    }

    /** Rally and Momentum are accepted only by the declaration operation, never as an action. */
    static boolean isDeclarationOnly(SpecialAction action) {
        return action == SpecialAction.RALLY || action == SpecialAction.MOMENTUM;
    }
}
