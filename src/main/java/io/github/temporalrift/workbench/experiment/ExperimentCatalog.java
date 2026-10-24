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
}
