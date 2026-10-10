package io.github.temporalrift.workbench.experiment.domain;

import java.util.UUID;

/** The requested experiment does not exist. */
public class ExperimentNotFoundException extends RuntimeException {

    public ExperimentNotFoundException(UUID experimentId) {
        super("Experiment " + experimentId + " was not found");
    }
}
