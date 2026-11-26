package io.github.temporalrift.workbench.analysis.domain.port.out;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** The scoring rules a frozen experiment's variant played under. */
public interface VariantScoring {

    /** The variant's rules, or empty for an unknown experiment or variant. */
    Optional<Rules> rules(UUID experimentId, String variantLabel);

    /**
     * @param scoreDeltas the configured points per score reason
     * @param maxEras the last era of the game's arc
     */
    record Rules(Map<String, Integer> scoreDeltas, int maxEras) {

        public Rules {
            scoreDeltas = Map.copyOf(scoreDeltas);
        }
    }
}
