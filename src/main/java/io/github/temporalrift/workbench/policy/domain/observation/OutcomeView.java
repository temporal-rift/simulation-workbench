package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;
import java.util.UUID;

/**
 * An outcome as the participant sees it: its public printed starting weight, its public band of the current era once
 * published (else null), and, only when the participant earned it (for example through Scan), the exact current
 * weight.
 */
public record OutcomeView(UUID outcomeId, int printedWeight, Integer scannedWeight, PublicBand band) {

    public OutcomeView {
        Objects.requireNonNull(outcomeId, "outcomeId");
        if (printedWeight < 0 || printedWeight > 100) {
            throw new IllegalArgumentException("printedWeight must be 0..100");
        }
    }

    /** An outcome whose band the current era has not published. */
    public OutcomeView(UUID outcomeId, int printedWeight, Integer scannedWeight) {
        this(outcomeId, printedWeight, scannedWeight, null);
    }

    /** The best weight the participant legitimately knows. */
    public int knownWeight() {
        return scannedWeight != null ? scannedWeight : printedWeight;
    }
}
