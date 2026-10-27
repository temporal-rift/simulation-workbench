package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.module;

import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.CaseReproducer;
import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.ReproductionOutcome;
import io.github.temporalrift.workbench.execution.application.port.in.GetReproductionUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseOnLaneUseCase;
import io.github.temporalrift.workbench.execution.domain.reproduction.LaneNotEquivalentException;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.run.RunIdempotencyConflictException;
import io.github.temporalrift.workbench.execution.domain.run.RunNotFoundException;
import io.github.temporalrift.workbench.execution.infrastructure.serialization.DivergenceWire;

/** Offers the reproduction use cases to other modules in the shapes they may see. */
public class CaseReproducerAdapter implements CaseReproducer {

    private final ReproduceCaseOnLaneUseCase reproduceOnLane;
    private final GetReproductionUseCase getReproduction;

    public CaseReproducerAdapter(ReproduceCaseOnLaneUseCase reproduceOnLane, GetReproductionUseCase getReproduction) {
        this.reproduceOnLane = reproduceOnLane;
        this.getReproduction = getReproduction;
    }

    @Override
    public ReproductionOutcome reproduceOn(UUID runId, UUID caseId, String laneId, UUID idempotencyKey) {
        try {
            return outcome(reproduceOnLane.handle(
                    new ReproduceCaseOnLaneUseCase.Command(runId, caseId, laneId, idempotencyKey)));
        } catch (LaneNotEquivalentException
                | ManifestMismatchException
                | RunNotFoundException
                | RunIdempotencyConflictException e) {
            throw new ExecutionRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<ReproductionOutcome> reproduction(UUID reproductionId) {
        return getReproduction.handle(reproductionId).map(CaseReproducerAdapter::outcome);
    }

    private static ReproductionOutcome outcome(Reproduction reproduction) {
        var failure = reproduction.failure();
        return new ReproductionOutcome(
                reproduction.reproductionId(),
                reproduction.runId(),
                reproduction.caseId(),
                reproduction.laneId(),
                ReproductionOutcome.Status.valueOf(reproduction.state().name()),
                reproduction.firstDivergence() == null ? null : DivergenceWire.report(reproduction.firstDivergence()),
                failure == null ? null : failure.code(),
                failure == null ? null : failure.message());
    }
}
