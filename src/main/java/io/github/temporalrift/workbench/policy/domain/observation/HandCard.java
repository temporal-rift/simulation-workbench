package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;

/** A card in the participant's own hand and whether its view marked it playable in the open round. */
public record HandCard(DealtCard card, boolean playable) {

    public HandCard {
        Objects.requireNonNull(card, "card");
    }
}
