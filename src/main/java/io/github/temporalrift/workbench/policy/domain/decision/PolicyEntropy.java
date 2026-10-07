package io.github.temporalrift.workbench.policy.domain.decision;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.SplittableRandom;

/**
 * Deterministic policy entropy. A stream is derived from the policy seed, the seat, the window and
 * the rejection count under its own domain-separation label, so it is stable for equal inputs and
 * can never coincide with a service entropy stream, which is not an input.
 */
public final class PolicyEntropy {

    private static final String DOMAIN = "temporal-rift/policy-entropy/v1";

    private final SplittableRandom random;

    private PolicyEntropy(long streamSeed) {
        this.random = new SplittableRandom(streamSeed);
    }

    public static PolicyEntropy derive(long policySeed, int seatIndex, String windowKey, int rejectionCount) {
        var material = DOMAIN + "|" + Long.toUnsignedString(policySeed) + "|" + seatIndex + "|" + windowKey + "|"
                + rejectionCount;
        try {
            var hash = MessageDigest.getInstance("SHA-256").digest(material.getBytes(StandardCharsets.UTF_8));
            return new PolicyEntropy(ByteBuffer.wrap(hash).getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** A uniform index in {@code [0, bound)}. */
    public int nextIndex(int bound) {
        return random.nextInt(bound);
    }
}
