package io.github.temporalrift.workbench.policy.domain.observation;

import java.util.Objects;

/** An outcome's public band of the current era and the round it was observed in. */
public record PublicBand(Band band, int observedInRound) {

    public PublicBand {
        Objects.requireNonNull(band, "band");
        if (observedInRound < 1 || observedInRound > 3) {
            throw new IllegalArgumentException("observedInRound must be 1..3");
        }
    }
}
