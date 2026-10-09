package io.github.temporalrift.workbench.analysis.domain.game;

import java.util.Comparator;
import java.util.Objects;

/** A card's type and grade, the unit card metrics are split by. */
public record CardKey(CardType type, CardGrade grade) implements Comparable<CardKey> {

    private static final Comparator<CardKey> ORDER =
            Comparator.comparing(CardKey::type).thenComparing(CardKey::grade);

    public CardKey {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(grade, "grade");
    }

    @Override
    public int compareTo(CardKey other) {
        return ORDER.compare(this, other);
    }
}
