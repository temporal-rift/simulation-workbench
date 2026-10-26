package io.github.temporalrift.workbench.experiment.application.query;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.ExperimentBounds;
import io.github.temporalrift.workbench.experiment.ExperimentCase;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentSeat;
import io.github.temporalrift.workbench.experiment.ExperimentSnapshot;
import io.github.temporalrift.workbench.experiment.application.port.in.GetVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.MatrixPreviewService;
import io.github.temporalrift.workbench.experiment.domain.StoredManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;

/** Reads frozen experiments for other modules; the stored manifest was validated when it was frozen. */
public class ExperimentCatalogQueryHandler implements ExperimentCatalog {

    private final ExperimentRepository experiments;
    private final GetVariantRulesUseCase variantRules;

    public ExperimentCatalogQueryHandler(ExperimentRepository experiments, GetVariantRulesUseCase variantRules) {
        this.experiments = experiments;
        this.variantRules = variantRules;
    }

    @Override
    public Optional<ExperimentBounds> bounds(UUID experimentId) {
        return experiments.findById(experimentId).map(StoredManifests::bounds);
    }

    @Override
    public Optional<ExperimentSnapshot> snapshot(UUID experimentId) {
        return experiments.findById(experimentId).map(stored -> {
            var tree = StoredManifests.tree(stored);
            var cases = MatrixPreviewService.preview(tree, stored.manifestDigest()).stream()
                    .map(ExperimentCatalogQueryHandler::toCase)
                    .toList();
            return new ExperimentSnapshot(StoredManifests.bounds(stored, tree), cases);
        });
    }

    @Override
    public Optional<EffectiveRules> variantRules(UUID experimentId, String variantLabel) {
        try {
            return Optional.of(variantRules.handle(experimentId, variantLabel));
        } catch (ExperimentNotFoundException _) {
            return Optional.empty();
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
