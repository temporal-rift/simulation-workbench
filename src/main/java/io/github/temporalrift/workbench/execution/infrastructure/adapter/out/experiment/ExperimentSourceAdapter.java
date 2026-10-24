package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.experiment.ExperimentBounds;
import io.github.temporalrift.workbench.experiment.ExperimentCase;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;

/** Reads frozen experiments through the experiment module's public API. */
public class ExperimentSourceAdapter implements ExperimentSource {

    private final ExperimentCatalog catalog;

    public ExperimentSourceAdapter(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<Plan> plan(UUID experimentId) {
        return catalog.bounds(experimentId).map(ExperimentSourceAdapter::toPlan);
    }

    @Override
    public Optional<Snapshot> snapshot(UUID experimentId) {
        return catalog.snapshot(experimentId)
                .map(snapshot -> new Snapshot(
                        toPlan(snapshot.bounds()),
                        snapshot.cases().stream()
                                .map(ExperimentSourceAdapter::toPlannedCase)
                                .toList()));
    }

    private static Plan toPlan(ExperimentBounds bounds) {
        return new Plan(
                bounds.manifestDigest(),
                bounds.manifestJson(),
                bounds.concurrency(),
                bounds.caseWallTimeoutSeconds(),
                bounds.maxRejectedCandidatesPerWindow(),
                bounds.variants().stream()
                        .map(variant -> new Variant(
                                variant.label(),
                                new BundleDigests(variant.effectiveRulesDigest(), variant.effectiveContentDigest())))
                        .toList());
    }

    private static PlannedCase toPlannedCase(ExperimentCase experimentCase) {
        return new PlannedCase(
                experimentCase.caseKey(),
                experimentCase.seed(),
                experimentCase.variantLabel(),
                experimentCase.playerCount(),
                experimentCase.seats().stream()
                        .map(seat ->
                                new SeatPlan(seat.seatIndex(), seat.faction(), seat.policyId(), seat.policyVersion()))
                        .toList());
    }
}
