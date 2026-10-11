package io.github.temporalrift.workbench.experiment.application.query;

import io.github.temporalrift.workbench.experiment.application.port.in.ListExperimentsUseCase;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.shared.Page;

public class ListExperimentsQueryHandler implements ListExperimentsUseCase {

    private final ExperimentRepository experiments;

    public ListExperimentsQueryHandler(ExperimentRepository experiments) {
        this.experiments = experiments;
    }

    @Override
    public Page<Summary> handle(int limit, int offset) {
        var items = experiments.findNewest(limit, offset).stream()
                .map(stored ->
                        new Summary(stored.experimentId(), stored.name(), stored.manifestDigest(), stored.createdAt()))
                .toList();
        return new Page<>(items, experiments.count());
    }
}
