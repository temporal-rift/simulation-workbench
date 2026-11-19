package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;

import io.github.temporalrift.workbench.shared.game.SpecialAction;

/**
 * A faction special action the participant may submit in the open action round.
 *
 * <p>{@code unavailableReason} is the reason the table reports the special unavailable this round, or null when it
 * reports it available or does not report it. The report is advisory: the game still decides each submission.
 */
public record PlayableSpecial(SpecialAction action, TargetShape shape, String unavailableReason) {

    public PlayableSpecial {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(shape, "shape");
    }

    /** A special the table does not report unavailable. */
    public PlayableSpecial(SpecialAction action, TargetShape shape) {
        this(action, shape, null);
    }

    /** Whether the table reports the special unavailable this round. */
    public boolean unavailable() {
        return unavailableReason != null;
    }
}
