package io.github.temporalrift.workbench.experiment.domain.port.out;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;

/** Driven port to the parameter space versions the workbench serves. */
public interface ParameterSpaces {

    /** Every served version, ascending. */
    List<ParameterSpace> all();

    Optional<ParameterSpace> find(int version);
}
