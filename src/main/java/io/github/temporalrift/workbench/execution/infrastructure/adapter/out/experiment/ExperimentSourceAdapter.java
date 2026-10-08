package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;

/** Reads frozen experiments through the experiment module's public API. */
public class ExperimentSourceAdapter implements ExperimentSource {

    private final ExperimentCatalog catalog;

    public ExperimentSourceAdapter(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<Plan> plan(UUID experimentId) {
        return catalog.bounds(experimentId)
                .map(bounds -> new Plan(
                        bounds.manifestDigest(),
                        bounds.concurrency(),
                        bounds.caseWallTimeoutSeconds(),
                        bounds.maxRejectedCandidatesPerWindow()));
    }

    @Override
    public Optional<List<PlannedCase>> cases(UUID experimentId) {
        return catalog.cases(experimentId)
                .map(cases -> cases.stream()
                        .map(experimentCase -> new PlannedCase(
                                experimentCase.caseKey(),
                                experimentCase.seed(),
                                experimentCase.variantLabel(),
                                experimentCase.playerCount(),
                                experimentCase.seats().stream()
                                        .map(seat -> new SeatPlan(
                                                seat.seatIndex(),
                                                seat.faction(),
                                                seat.policyId(),
                                                seat.policyVersion()))
                                        .toList()))
                        .toList());
    }
}
