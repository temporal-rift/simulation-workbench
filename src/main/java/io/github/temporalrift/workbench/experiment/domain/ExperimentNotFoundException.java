package io.github.temporalrift.workbench.experiment.domain;

import java.util.UUID;

/** The requested experiment, or something of it, does not exist. */
public class ExperimentNotFoundException extends RuntimeException {

    public ExperimentNotFoundException(UUID experimentId) {
        super("Experiment " + experimentId + " was not found");
    }

    /** The experiment exists but has no variant with this label, or no rules retained for it. */
    public ExperimentNotFoundException(UUID experimentId, String label) {
        super("Experiment " + experimentId + " has no variant " + label + " with retained rules");
    }
}
