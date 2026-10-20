package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.experiment.application.port.in.GetParameterSpaceUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListParameterSpacesUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.ParameterSpacesApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterSpace;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ParameterSpaceCatalog;

@RestController
class ParameterSpaceController implements ParameterSpacesApi {

    private final ListParameterSpacesUseCase listParameterSpacesUseCase;
    private final GetParameterSpaceUseCase getParameterSpaceUseCase;

    ParameterSpaceController(
            ListParameterSpacesUseCase listParameterSpacesUseCase, GetParameterSpaceUseCase getParameterSpaceUseCase) {
        this.listParameterSpacesUseCase = listParameterSpacesUseCase;
        this.getParameterSpaceUseCase = getParameterSpaceUseCase;
    }

    @Override
    public ResponseEntity<ParameterSpaceCatalog> listParameterSpaces() {
        return ResponseEntity.ok(new ParameterSpaceCatalog(listParameterSpacesUseCase.handle().stream()
                .map(VariantRulesMapper::space)
                .toList()));
    }

    @Override
    public ResponseEntity<ParameterSpace> getParameterSpace(Integer version) {
        return ResponseEntity.ok(VariantRulesMapper.space(getParameterSpaceUseCase.handle(version)));
    }
}
