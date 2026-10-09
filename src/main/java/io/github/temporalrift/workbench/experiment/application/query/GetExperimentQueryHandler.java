package io.github.temporalrift.workbench.experiment.application.query;

import java.util.UUID;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.application.port.in.GetExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

public class GetExperimentQueryHandler implements GetExperimentUseCase {

    private final ExperimentRepository experiments;
    private final ObjectMapper objectMapper;

    public GetExperimentQueryHandler(ExperimentRepository experiments, ObjectMapper objectMapper) {
        this.experiments = experiments;
        this.objectMapper = objectMapper;
    }

    @Override
    public View handle(UUID experimentId) {
        var stored =
                experiments.findById(experimentId).orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        try {
            return new View(
                    stored.experimentId(),
                    objectMapper.readTree(stored.manifestJson()),
                    stored.manifestDigest(),
                    stored.createdAt());
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored manifest is not valid JSON: " + experimentId, e);
        }
    }
}
