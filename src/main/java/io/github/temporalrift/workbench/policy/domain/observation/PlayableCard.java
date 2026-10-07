package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;

/**
 * A hand card playable in the open action round. {@code targetCount} is the number of events (1-3)
 * or participants (1-2) the list shapes require; it is 1 for every other shape.
 */
public record PlayableCard(DealtCard card, TargetShape shape, int targetCount) {

    public PlayableCard {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(shape, "shape");
        if (targetCount < 1 || targetCount > 3) {
            throw new IllegalArgumentException("targetCount must be 1..3");
        }
    }
}
