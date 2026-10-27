package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.execution;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.CaseReproducer;
import io.github.temporalrift.workbench.execution.DivergenceReport;
import io.github.temporalrift.workbench.execution.ExecutionRefusedException;
import io.github.temporalrift.workbench.execution.ReproductionOutcome;
import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.execution.RunSummary;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityPlaybackRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityPlayback;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Plays and reproduces cases through the execution module's public API. */
public class FidelityPlaybackAdapter implements FidelityPlayback {

    static final String CASE_NOT_SUCCEEDED = "CASE_NOT_SUCCEEDED";

    private final RunStarter runs;
    private final RunCatalog catalog;
    private final CaseReproducer reproducer;

    public FidelityPlaybackAdapter(RunStarter runs, RunCatalog catalog, CaseReproducer reproducer) {
        this.runs = runs;
        this.catalog = catalog;
        this.reproducer = reproducer;
    }

    @Override
    public UUID startRun(UUID experimentId, UUID idempotencyKey) {
        try {
            return runs.start(experimentId, idempotencyKey);
        } catch (ExecutionRefusedException e) {
            throw new FidelityPlaybackRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<RunStatus> run(UUID runId) {
        return catalog.run(runId).map(FidelityPlaybackAdapter::status);
    }

    @Override
    public void resumeRun(UUID runId) {
        try {
            runs.resume(runId);
        } catch (ExecutionRefusedException e) {
            throw new FidelityPlaybackRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public List<PlayedCase> cases(UUID runId) {
        return catalog.cases(runId).stream()
                .map(FidelityPlaybackAdapter::played)
                .toList();
    }

    @Override
    public UUID reproduce(UUID runId, UUID runCaseId, String laneId, UUID idempotencyKey) {
        try {
            return reproducer
                    .reproduceOn(runId, runCaseId, laneId, idempotencyKey)
                    .reproductionId();
        } catch (ExecutionRefusedException e) {
            throw new FidelityPlaybackRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public Optional<Reproduced> reproduction(UUID reproductionId) {
        return reproducer.reproduction(reproductionId).map(FidelityPlaybackAdapter::reproduced);
    }

    private static RunStatus status(RunSummary run) {
        return new RunStatus(
                switch (run.phase()) {
                    case ACTIVE -> RunStatus.Phase.ACTIVE;
                    case INTERRUPTED -> RunStatus.Phase.INTERRUPTED;
                    case COMPLETED -> RunStatus.Phase.COMPLETED;
                    case FAILED -> RunStatus.Phase.FAILED;
                    case CANCELLED -> RunStatus.Phase.CANCELLED;
                },
                run.failureMessage());
    }

    /**
     * The coordinate of a case from its seats: the factions ordered by name are the set, the position of seat 0's
     * faction in it is the cyclic rotation, and the policy is the one every seat is assigned.
     */
    private static PlayedCase played(RunCase runCase) {
        var set = runCase.seats().stream()
                .map(RunCase.Seat::faction)
                .sorted(Comparator.comparing(Faction::name))
                .toList();
        var seatZero = runCase.seats().stream()
                .filter(seat -> seat.seatIndex() == 0)
                .findFirst()
                .orElseThrow();
        var succeeded = runCase.state() == CaseState.SUCCEEDED;
        return new PlayedCase(
                runCase.caseId(),
                runCase.caseKey(),
                runCase.variantLabel(),
                runCase.playerCount(),
                set,
                set.indexOf(seatZero.faction()),
                seatZero.policyId(),
                succeeded,
                succeeded ? null : failure(runCase));
    }

    private static FidelityFailure failure(RunCase runCase) {
        var code = runCase.failureCode() == null
                ? CASE_NOT_SUCCEEDED
                : runCase.failureCode().name();
        return new FidelityFailure(code, "The case ended " + runCase.state() + " on the simulator");
    }

    private static Reproduced reproduced(ReproductionOutcome outcome) {
        return new Reproduced(
                Reproduced.Status.valueOf(outcome.status().name()),
                outcome.firstDivergence() == null ? null : divergence(outcome.firstDivergence()),
                outcome.failureCode() == null
                        ? null
                        : new FidelityFailure(outcome.failureCode().name(), outcome.failureMessage()));
    }

    private static FirstDivergence divergence(DivergenceReport report) {
        return new FirstDivergence(report.step(), report.kind(), report.expected(), report.actual());
    }
}
