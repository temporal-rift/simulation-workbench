package io.github.temporalrift.workbench.experiment.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.experiment.application.port.in.GetExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.StoredManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

public class GetExperimentQueryHandler implements GetExperimentUseCase {

    private final ExperimentRepository experiments;

    public GetExperimentQueryHandler(ExperimentRepository experiments) {
        this.experiments = experiments;
    }

    @Override
    public View handle(UUID experimentId) {
        var stored =
                experiments.findById(experimentId).orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        return new View(
                stored.experimentId(), StoredManifests.tree(stored), stored.manifestDigest(), stored.createdAt());
    }
}
