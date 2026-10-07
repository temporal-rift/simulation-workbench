package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;
import java.util.UUID;

/** A card in the participant's own hand, deal or offer. */
public record DealtCard(UUID cardInstanceId, CardType type, CardGrade grade, CardCategory category) {

    public DealtCard {
        Objects.requireNonNull(cardInstanceId, "cardInstanceId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(grade, "grade");
        Objects.requireNonNull(category, "category");
    }
}
