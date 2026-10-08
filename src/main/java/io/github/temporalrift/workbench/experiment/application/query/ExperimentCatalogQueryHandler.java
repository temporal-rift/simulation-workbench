package io.github.temporalrift.workbench.experiment.application.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.ExperimentBounds;
import io.github.temporalrift.workbench.experiment.ExperimentCase;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentSeat;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

/** Reads frozen experiments for other modules; the stored manifest was validated when it was frozen. */
public class ExperimentCatalogQueryHandler implements ExperimentCatalog {

    private final ExperimentRepository experiments;
    private final ObjectMapper objectMapper;

    public ExperimentCatalogQueryHandler(ExperimentRepository experiments, ObjectMapper objectMapper) {
        this.experiments = experiments;
        this.objectMapper = objectMapper;
    }

    @Override
    public Optional<ExperimentBounds> bounds(UUID experimentId) {
        return experiments.findById(experimentId).map(stored -> {
            var manifest = parse(stored);
            return new ExperimentBounds(
                    stored.manifestDigest(),
                    manifest.get("concurrency").asInt(),
                    manifest.get("caseWallTimeoutSeconds").asInt(),
                    manifest.get("maxRejectedCandidatesPerWindow").asInt());
        });
    }

    @Override
    public Optional<List<ExperimentCase>> cases(UUID experimentId) {
        return experiments
                .findById(experimentId)
                .map(stored -> MatrixPreviewService.preview(parse(stored), stored.manifestDigest()).stream()
                        .map(ExperimentCatalogQueryHandler::toCase)
                        .toList());
    }

    private JsonNode parse(ExperimentRepository.StoredExperiment stored) {
        try {
            return objectMapper.readTree(stored.manifestJson());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored manifest is not valid JSON: " + stored.experimentId(), e);
        }
    }

    private static ExperimentCase toCase(MatrixPreviewService.CaseCoordinate coordinate) {
        return new ExperimentCase(
                coordinate.caseKey(),
                coordinate.seed(),
                coordinate.variantLabel(),
                coordinate.playerCount(),
                coordinate.seats().stream()
                        .map(seat -> new ExperimentSeat(
                                seat.seatIndex(), seat.faction(), seat.policyId(), seat.policyVersion()))
                        .toList());
    }
}
