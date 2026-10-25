package io.github.temporalrift.workbench.experiment;

import java.util.Optional;
import java.util.UUID;

/** Public API of the experiment module: what other modules may read of a frozen experiment. */
public interface ExperimentCatalog {

    /** The execution inputs the frozen manifest fixes for every case, or empty for an unknown experiment. */
    Optional<ExperimentBounds> bounds(UUID experimentId);

    /**
     * The bounds and every case of the frozen experiment's matrix in stable order, from one stored read and one
     * parse, or empty for an unknown experiment.
     */
    Optional<ExperimentSnapshot> snapshot(UUID experimentId);

    /**
     * The effective rules of a variant of the frozen experiment, rebuilt from the baseline document retained with it,
     * or empty when the experiment or variant is unknown or retains no such document.
     */
    Optional<EffectiveRules> variantRules(UUID experimentId, String variantLabel);
}
