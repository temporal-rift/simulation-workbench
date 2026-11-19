package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;

/**
 * A hand card playable in the open action round. {@code targetCount} is the number of events or participants the list
 * shapes require: up to as many events as the era holds, and 1-2 participants; it is 1 for every other shape.
 */
public record PlayableCard(DealtCard card, TargetShape shape, int targetCount) {

    public PlayableCard {
        Objects.requireNonNull(card, "card");
        Objects.requireNonNull(shape, "shape");
        if (targetCount < 1) {
            throw new IllegalArgumentException("targetCount must be at least 1");
        }
    }
}
