package io.github.temporalrift.workbench.experiment.application.port.in;

import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;

/** Reads one served parameter space version. */
public interface GetParameterSpaceUseCase {

    ParameterSpace handle(int version);
}
