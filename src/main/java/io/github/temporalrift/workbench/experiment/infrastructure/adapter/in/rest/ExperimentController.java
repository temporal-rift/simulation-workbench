package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest.v1.ExperimentsApi;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest.v1.model.Experiment;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest.v1.model.ExperimentManifest;

@RestController
class ExperimentController implements ExperimentsApi {

    private final CreateExperimentUseCase createExperimentUseCase;
    private final ObjectMapper objectMapper;

    ExperimentController(CreateExperimentUseCase createExperimentUseCase, ObjectMapper objectMapper) {
        this.createExperimentUseCase = createExperimentUseCase;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ResponseEntity<Experiment> createExperiment(UUID idempotencyKey, ExperimentManifest experimentManifest) {
        JsonNode manifest = objectMapper.convertValue(experimentManifest, JsonNode.class);
        var result = createExperimentUseCase.handle(new CreateExperimentUseCase.Command(idempotencyKey, manifest));
        var response = new Experiment();
        response.setExperimentId(result.experimentId());
        response.setManifest(objectMapper.convertValue(result.manifest(), ExperimentManifest.class));
        response.setManifestDigest(result.manifestDigest());
        response.setCreatedAt(OffsetDateTime.parse(result.createdAt()).withOffsetSameInstant(ZoneOffset.UTC));
        return ResponseEntity.status(201).body(response);
    }
}
