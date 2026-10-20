package io.github.temporalrift.workbench.experiment.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;

/** Lists every parameter space version the workbench serves, ascending. */
public interface ListParameterSpacesUseCase {

    List<ParameterSpace> handle();
}
