package io.github.temporalrift.workbench.experiment.application.query;

import java.util.List;

import io.github.temporalrift.workbench.experiment.application.port.in.GetParameterSpaceUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListParameterSpacesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ParameterSpaceNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;

/** Serves the parameter space versions as configured. */
public class ParameterSpaceQueryHandler implements ListParameterSpacesUseCase, GetParameterSpaceUseCase {

    private final ParameterSpaces spaces;

    public ParameterSpaceQueryHandler(ParameterSpaces spaces) {
        this.spaces = spaces;
    }

    @Override
    public List<ParameterSpace> handle() {
        return spaces.all();
    }

    @Override
    public ParameterSpace handle(int version) {
        return spaces.find(version).orElseThrow(() -> new ParameterSpaceNotFoundException(version));
    }
}
