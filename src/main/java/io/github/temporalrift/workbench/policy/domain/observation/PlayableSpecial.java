package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;

/** A faction special action the participant may submit in the open action round. */
public record PlayableSpecial(SpecialAction action, TargetShape shape) {

    public PlayableSpecial {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(shape, "shape");
    }
}
