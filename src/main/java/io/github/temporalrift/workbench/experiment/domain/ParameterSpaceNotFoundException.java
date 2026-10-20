package io.github.temporalrift.workbench.experiment.domain;

/** The requested parameter space version is not served. */
public class ParameterSpaceNotFoundException extends RuntimeException {

    public ParameterSpaceNotFoundException(int version) {
        super("Parameter space " + version + " was not found");
    }
}
