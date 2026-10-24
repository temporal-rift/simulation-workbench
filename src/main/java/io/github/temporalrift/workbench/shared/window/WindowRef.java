package io.github.temporalrift.workbench.shared.window;

import java.util.Comparator;
import java.util.Objects;

/**
 * Identifies one decision window of a game: its kind, its era and, for an action round only, the round.
 * Natural order follows the game: by era, then window kind, then round.
 */
public record WindowRef(WindowKind kind, int era, Integer round) implements Comparable<WindowRef> {

    private static final Comparator<WindowRef> ORDER = Comparator.comparingInt(WindowRef::era)
            .thenComparing(WindowRef::kind)
            .thenComparing(WindowRef::round, Comparator.nullsFirst(Comparator.naturalOrder()));

    public WindowRef {
        Objects.requireNonNull(kind, "kind");
        if ((kind == WindowKind.ACTION_ROUND) != (round != null)) {
            throw new IllegalArgumentException(
                    kind == WindowKind.ACTION_ROUND
                            ? "An " + kind + " window needs a round, but the round is " + round
                            : "A " + kind + " window has no round, but the round is " + round);
        }
    }

    /** A window that has no round. */
    public static WindowRef of(WindowKind kind, int era) {
        return new WindowRef(kind, era, null);
    }

    /** An action round of the era. */
    public static WindowRef actionRound(int era, int round) {
        return new WindowRef(WindowKind.ACTION_ROUND, era, round);
    }

    /**
     * A stable human-readable label such as {@code era1/round2/action}. It separates policy entropy streams and
     * labels the window in published payloads; it is never parsed back.
     */
    public String label() {
        return "era" + era + "/" + (round == null ? "" : "round" + round + "/") + kind.slug();
    }

    @Override
    public int compareTo(WindowRef other) {
        return ORDER.compare(this, other);
    }
}
