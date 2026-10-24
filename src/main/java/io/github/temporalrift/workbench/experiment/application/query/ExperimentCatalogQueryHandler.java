package io.github.temporalrift.workbench.experiment.application.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.experiment.ExperimentBounds;
import io.github.temporalrift.workbench.experiment.ExperimentCase;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentSeat;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.StoredManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

/** Reads frozen experiments for other modules; the stored manifest was validated when it was frozen. */
public class ExperimentCatalogQueryHandler implements ExperimentCatalog {

    private final ExperimentRepository experiments;

    public ExperimentCatalogQueryHandler(ExperimentRepository experiments) {
        this.experiments = experiments;
    }

    @Override
    public Optional<ExperimentBounds> bounds(UUID experimentId) {
        return experiments.findById(experimentId).map(StoredManifests::bounds);
    }

    @Override
    public Optional<List<ExperimentCase>> cases(UUID experimentId) {
        return experiments
                .findById(experimentId)
                .map(stored ->
                        MatrixPreviewService.preview(StoredManifests.tree(stored), stored.manifestDigest()).stream()
                                .map(ExperimentCatalogQueryHandler::toCase)
                                .toList());
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
