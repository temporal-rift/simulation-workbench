package io.github.temporalrift.workbench.execution.application.query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.CaseEvidence;
import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunSummary;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CountingGame;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

/** Reads runs, their cases and the evidence of succeeded cases for other modules. */
public class RunCatalogQueryHandler implements RunCatalog {

    private final RunRepository runs;
    private final EvidenceLedger evidence;

    public RunCatalogQueryHandler(RunRepository runs, EvidenceLedger evidence) {
        this.runs = runs;
        this.evidence = evidence;
    }

    @Override
    public Optional<RunSummary> run(UUID runId) {
        return runs.find(runId)
                .map(run -> new RunSummary(
                        run.runId(),
                        run.experimentId(),
                        phase(run.state()),
                        run.failure() == null ? null : run.failure().message()));
    }

    @Override
    public List<RunCase> cases(UUID runId) {
        var failureCodes = runs.finalFailureCodes(runId);
        return runs.cases(runId).stream()
                .map(logicalCase -> runCase(logicalCase, failureCodes.get(logicalCase.caseId())))
                .toList();
    }

    @Override
    public Optional<CaseEvidence> evidence(UUID runId, UUID caseId) {
        return runs.findCase(runId, caseId)
                .filter(logicalCase -> logicalCase.state() == CaseState.SUCCEEDED)
                .flatMap(logicalCase -> CountingGame.of(runs.attemptsOf(caseId)))
                .map(gameId -> new CaseEvidence(evidence.steps(caseId, gameId), evidence.events(caseId, gameId)));
    }

    private static RunPhase phase(RunState state) {
        return switch (state) {
            case QUEUED, RUNNING, INTERRUPTED, CANCELLING -> RunPhase.ACTIVE;
            case COMPLETED -> RunPhase.COMPLETED;
            case FAILED -> RunPhase.FAILED;
            case CANCELLED -> RunPhase.CANCELLED;
        };
    }

    private static RunCase runCase(LogicalCase logicalCase, FailureCode failureCode) {
        return new RunCase(
                logicalCase.caseId(),
                logicalCase.caseKey(),
                logicalCase.variantLabel(),
                logicalCase.seed(),
                logicalCase.playerCount(),
                logicalCase.seats().stream()
                        .map(seat -> new RunCase.Seat(
                                seat.seatIndex(), seat.faction(), seat.policyId(), seat.policyVersion()))
                        .toList(),
                logicalCase.state(),
                logicalCase.result() == null ? null : result(logicalCase.result()),
                failureCode);
    }

    private static RunCase.Result result(CaseResult result) {
        return new RunCase.Result(
                result.endReason(),
                result.winners().stream().map(Winner::seatIndex).toList(),
                result.finalScores().stream()
                        .map(score -> new RunCase.Score(score.seatIndex(), score.score()))
                        .toList(),
                result.eras(),
                result.rounds(),
                result.decisions());
    }
}
