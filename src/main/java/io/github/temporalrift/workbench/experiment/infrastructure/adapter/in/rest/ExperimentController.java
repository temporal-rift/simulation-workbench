package io.github.temporalrift.workbench.experiment.infrastructure.adapter.in.rest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.GetExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListExperimentsUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.ExperimentsApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Experiment;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentList;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentManifest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentPreview;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ExperimentSummary;

@RestController
class ExperimentController implements ExperimentsApi {

    private final CreateExperimentUseCase createExperimentUseCase;
    private final GetExperimentUseCase getExperimentUseCase;
    private final ListExperimentsUseCase listExperimentsUseCase;
    private final PreviewExperimentUseCase previewExperimentUseCase;
    private final ObjectMapper objectMapper;

    ExperimentController(
            CreateExperimentUseCase createExperimentUseCase,
            GetExperimentUseCase getExperimentUseCase,
            ListExperimentsUseCase listExperimentsUseCase,
            PreviewExperimentUseCase previewExperimentUseCase,
            ObjectMapper objectMapper) {
        this.createExperimentUseCase = createExperimentUseCase;
        this.getExperimentUseCase = getExperimentUseCase;
        this.listExperimentsUseCase = listExperimentsUseCase;
        this.previewExperimentUseCase = previewExperimentUseCase;
        this.objectMapper = objectMapper;
    }

    @Override
    public ResponseEntity<Experiment> createExperiment(UUID idempotencyKey, ExperimentManifest experimentManifest) {
        JsonNode manifest = objectMapper.convertValue(experimentManifest, JsonNode.class);
        var result = createExperimentUseCase.handle(new CreateExperimentUseCase.Command(idempotencyKey, manifest));
        return ResponseEntity.status(201)
                .body(experiment(
                        result.experimentId(),
                        result.manifest(),
                        result.manifestDigest(),
                        Instant.parse(result.createdAt())));
    }

    @Override
    public ResponseEntity<Experiment> getExperiment(UUID experimentId) {
        var view = getExperimentUseCase.handle(experimentId);
        return ResponseEntity.ok(
                experiment(view.experimentId(), view.manifest(), view.manifestDigest(), view.createdAt()));
    }

    @Override
    public ResponseEntity<ExperimentList> listExperiments(Integer limit, Integer offset) {
        var page = listExperimentsUseCase.handle(limit, offset);
        return ResponseEntity.ok(new ExperimentList(
                page.items().stream()
                        .map(summary -> new ExperimentSummary(
                                summary.experimentId(),
                                summary.name(),
                                summary.manifestDigest(),
                                utc(summary.createdAt())))
                        .toList(),
                Math.toIntExact(page.total())));
    }

    @Override
    public ResponseEntity<ExperimentPreview> previewExperiment(
            ExperimentManifest experimentManifest, Integer limit, Integer offset) {
        JsonNode manifest = objectMapper.convertValue(experimentManifest, JsonNode.class);
        var preview = previewExperimentUseCase.handle(new PreviewExperimentUseCase.Query(manifest, limit, offset));
        return ResponseEntity.ok(ExperimentPreviewMapper.toApi(preview));
    }

    private Experiment experiment(UUID experimentId, JsonNode manifest, String manifestDigest, Instant createdAt) {
        var response = new Experiment();
        response.setExperimentId(experimentId);
        response.setManifest(objectMapper.convertValue(manifest, ExperimentManifest.class));
        response.setManifestDigest(manifestDigest);
        response.setCreatedAt(utc(createdAt));
        return response;
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
