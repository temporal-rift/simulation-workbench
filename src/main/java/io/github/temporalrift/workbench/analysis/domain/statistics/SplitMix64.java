package io.github.temporalrift.workbench.analysis.domain.statistics;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A SplitMix64 stream whose start is the SHA-256 of length-prefixed UTF-8 parts, so distinct parts can never
 * alias and the same parts always replay the same draws on any JVM.
 */
public final class SplitMix64 {

    private static final long GOLDEN_GAMMA = 0x9E3779B97F4A7C15L;

    private long state;

    private SplitMix64(long state) {
        this.state = state;
    }

    public static SplitMix64 seededBy(String... parts) {
        try {
            var sha256 = MessageDigest.getInstance("SHA-256");
            for (var part : parts) {
                var bytes = part.getBytes(StandardCharsets.UTF_8);
                sha256.update(
                        ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
                sha256.update(bytes);
            }
            return new SplitMix64(ByteBuffer.wrap(sha256.digest()).getLong());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is a mandatory JDK algorithm", e);
        }
    }

    public long nextLong() {
        state += GOLDEN_GAMMA;
        var z = state;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    /** A uniform draw in {@code [0, bound)} without modulo bias (Lemire's rejection method). */
    public int nextInt(int bound) {
        if (bound <= 0) {
            throw new IllegalArgumentException("bound must be positive");
        }
        var range = (long) bound;
        var draw = nextLong();
        var low = draw * range;
        if (Long.compareUnsigned(low, range) < 0) {
            var threshold = Long.remainderUnsigned(-range, range);
            while (Long.compareUnsigned(low, threshold) < 0) {
                draw = nextLong();
                low = draw * range;
            }
        }
        return (int) Math.unsignedMultiplyHigh(draw, range);
    }
}
