package io.github.temporalrift.workbench.execution.domain.evidence;

import java.util.Objects;

import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * The coordinates of the policy entropy stream a decision drew from.
 *
 * @param stream the entropy derivation the draw followed
 * @param policySeed the case seed the policy derived its entropy from
 * @param drawIndex how many rejections preceded the decision in its window
 */
public record StepEntropy(String stream, String policySeed, int seat, WindowRef window, int drawIndex) {

    /** The derivation {@code PolicyEntropy} implements. */
    public static final String POLICY_STREAM = "policy-entropy/v1";

    public StepEntropy {
        Objects.requireNonNull(stream, "stream");
        Objects.requireNonNull(policySeed, "policySeed");
        Objects.requireNonNull(window, "window");
    }

    /** The entropy of a policy decision. */
    public static StepEntropy ofPolicy(String policySeed, int seat, WindowRef window, int drawIndex) {
        return new StepEntropy(POLICY_STREAM, policySeed, seat, window, drawIndex);
    }
}
