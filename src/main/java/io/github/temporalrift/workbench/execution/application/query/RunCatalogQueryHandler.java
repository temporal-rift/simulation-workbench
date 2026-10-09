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
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.CountingGame;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.execution.domain.run.Winner;

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
        return runs.find(runId).map(run -> new RunSummary(run.runId(), run.experimentId()));
    }

    @Override
    public List<RunCase> cases(UUID runId) {
        return runs.cases(runId).stream().map(RunCatalogQueryHandler::runCase).toList();
    }

    @Override
    public Optional<CaseEvidence> evidence(UUID runId, UUID caseId) {
        return runs.findCase(runId, caseId)
                .filter(logicalCase -> logicalCase.state() == CaseState.SUCCEEDED)
                .flatMap(logicalCase -> CountingGame.of(runs.attemptsOf(caseId)))
                .map(gameId -> new CaseEvidence(
                        evidence.steps(caseId, gameId).stream()
                                .map(step -> new CaseEvidence.Step(
                                        step.seatIndex(),
                                        step.phase(),
                                        step.era(),
                                        step.round(),
                                        step.observation(),
                                        step.decision(),
                                        step.outcome().name(),
                                        step.outcomeCode()))
                                .toList(),
                        evidence.events(caseId, gameId).stream()
                                .map(event ->
                                        new CaseEvidence.Event(event.source(), event.eventType(), event.payload()))
                                .toList()));
    }

    private static RunCase runCase(LogicalCase logicalCase) {
        return new RunCase(
                logicalCase.caseId(),
                logicalCase.variantLabel(),
                logicalCase.seed(),
                logicalCase.playerCount(),
                logicalCase.seats().stream()
                        .map(seat -> new RunCase.Seat(
                                seat.seatIndex(), seat.faction(), seat.policyId(), seat.policyVersion()))
                        .toList(),
                logicalCase.state().name(),
                logicalCase.result() == null ? null : result(logicalCase.result()));
    }

    private static RunCase.Result result(CaseResult result) {
        return new RunCase.Result(
                result.endReason().name(),
                result.winners().stream().map(Winner::seatIndex).toList(),
                result.finalScores().stream()
                        .map(score -> new RunCase.Score(score.seatIndex(), score.score()))
                        .toList(),
                result.eras(),
                result.rounds(),
                result.decisions());
    }
}
