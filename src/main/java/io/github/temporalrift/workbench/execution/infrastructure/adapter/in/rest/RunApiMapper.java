package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import io.github.temporalrift.workbench.execution.application.port.in.CaseView;
import io.github.temporalrift.workbench.execution.application.port.in.RunView;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Attempt;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.AttemptState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseCounts;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseResult;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CaseSummary;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Failure;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FinalScore;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ModelCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Run;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RunState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SeatAssignment;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.TerminalEndReason;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Winner;

/** Maps application views to the published {@code simulation-api} representations. */
final class RunApiMapper {

    private RunApiMapper() {}

    static Run toApi(RunView view) {
        var run = view.run();
        var counts = view.counts();
        return new Run(
                run.runId(),
                run.experimentId(),
                RunState.valueOf(run.state().name()),
                new CaseCounts(
                        counts.requested(),
                        counts.pending(),
                        counts.running(),
                        counts.succeeded(),
                        counts.failed(),
                        counts.cancelled()),
                utc(run.createdAt()),
                utc(run.startedAt()),
                utc(run.finishedAt()),
                failure(run.failure()));
    }

    static ModelCase toApi(CaseView view) {
        var logicalCase = view.logicalCase();
        var result = logicalCase.result();
        return new ModelCase(
                logicalCase.caseId(),
                logicalCase.caseKey(),
                logicalCase.runId(),
                logicalCase.variantLabel(),
                logicalCase.seed(),
                ModelCase.PlayerCountEnum.fromValue(logicalCase.playerCount()),
                logicalCase.seats().stream()
                        .map(seat -> new SeatAssignment(
                                seat.seatIndex(),
                                Faction.fromValue(seat.faction()),
                                seat.policyId(),
                                seat.policyVersion()))
                        .toList(),
                CaseState.valueOf(logicalCase.state().name()),
                view.attempts().stream().map(RunApiMapper::attempt).toList(),
                result == null ? null : result(result));
    }

    static io.github.temporalrift.workbench.execution.domain.run.RunState toDomain(RunState state) {
        return state == null
                ? null
                : io.github.temporalrift.workbench.execution.domain.run.RunState.valueOf(state.name());
    }

    static io.github.temporalrift.workbench.execution.domain.run.CaseState toDomain(CaseState state) {
        return state == null
                ? null
                : io.github.temporalrift.workbench.execution.domain.run.CaseState.valueOf(state.name());
    }

    static CaseSummary toSummary(LogicalCase logicalCase) {
        var result = logicalCase.result();
        return new CaseSummary(
                logicalCase.caseId(),
                logicalCase.caseKey(),
                logicalCase.runId(),
                logicalCase.variantLabel(),
                logicalCase.seed(),
                CaseSummary.PlayerCountEnum.fromValue(logicalCase.playerCount()),
                CaseState.valueOf(logicalCase.state().name()),
                result == null
                        ? null
                        : CaseSummary.EndReasonEnum.fromValue(result.endReason().name()));
    }

    private static Attempt attempt(io.github.temporalrift.workbench.execution.domain.run.Attempt attempt) {
        return new Attempt(
                attempt.attemptId(),
                attempt.ordinal(),
                AttemptState.valueOf(attempt.state().name()),
                attempt.gameId(),
                null,
                failure(attempt.failure()));
    }

    private static CaseResult result(io.github.temporalrift.workbench.execution.domain.run.CaseResult result) {
        List<Winner> winners = result.winners().stream()
                .map(winner -> new Winner(
                        winner.seatIndex(),
                        Faction.fromValue(winner.faction()),
                        winner.winType() == null
                                ? null
                                : Winner.WinTypeEnum.fromValue(winner.winType().name())))
                .toList();
        List<FinalScore> scores = result.finalScores().stream()
                .map(score -> new FinalScore(score.seatIndex(), Faction.fromValue(score.faction()), score.score()))
                .toList();
        return new CaseResult(
                TerminalEndReason.fromValue(result.endReason().name()),
                winners,
                scores,
                result.eras(),
                result.rounds(),
                result.decisions(),
                result.semanticDigest());
    }

    private static Failure failure(io.github.temporalrift.workbench.execution.domain.run.Failure failure) {
        return failure == null ? null : new Failure(failure.code().name(), failure.message());
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }
}
