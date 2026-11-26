package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.Escalation;
import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringResult;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModelResult;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatEraRates;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RescoreRequest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Rescoring;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.RescoringPlayerCount;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringCell;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelRequest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringSensitivity;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SeatEraRatesBetReasonsInner;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SeatEraRatesOtherReasonsInner;

/** Maps the generated scoring-model request and response to and from the use case. */
final class ScoringModelApiMapper {

    private ScoringModelApiMapper() {}

    static ScoringEngine engine(
            io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringEngine engine) {
        var points = engine.getReasonPoints().stream()
                .map(item -> new ReasonPoints(item.getScoreReason(), item.getPoints()))
                .toList();
        var escalations = engine.getEscalations() == null
                ? List.<Escalation>of()
                : engine.getEscalations().stream()
                        .map(ScoringModelApiMapper::escalation)
                        .toList();
        return new ScoringEngine(engine.getMaxEras(), points, escalations);
    }

    /** The reasons arrive as an insertion-ordered set, so an item keeps the index the client submitted it at. */
    private static Escalation escalation(
            io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Escalation escalation) {
        return new Escalation(
                escalation.getName(),
                List.copyOf(escalation.getScoreReasons()),
                escalation.getBase(),
                escalation.getIncrement(),
                escalation.getResetOn().stream()
                        .map(outcome -> BetOutcome.valueOf(outcome.getValue()))
                        .collect(Collectors.toSet()));
    }

    static RescoreRunUseCase.Query rescoreQuery(UUID runId, RescoreRequest request) {
        return new RescoreRunUseCase.Query(
                runId, engine(request.getEngine()), request.getVariantLabel(), request.getTableId());
    }

    static Rescoring rescoring(RescoringResult result) {
        return new Rescoring()
                .complete(result.complete())
                .cells(result.cells().stream().map(ScoringModelApiMapper::cell).toList())
                .playerCounts(result.playerCounts().stream()
                        .map(counts -> new RescoringPlayerCount()
                                .playerCount(counts.playerCount())
                                .judgedCases(counts.judgedCases())
                                .truncatedCases(counts.truncatedCases())
                                .meanEras(counts.meanEras() == null ? null : decimal(counts.meanEras())))
                        .toList());
    }

    static EvaluateScoringModelUseCase.Query query(ScoringModelRequest request) {
        var factions = request.getFactions().stream()
                .map(ScoringModelApiMapper::faction)
                .sorted(Comparator.naturalOrder())
                .toList();
        var rates =
                request.getRates().stream().map(ScoringModelApiMapper::rates).toList();
        var sweep = request.getSweep() == null
                ? List.<EvaluateScoringModelUseCase.SweepParameter>of()
                : request.getSweep().stream()
                        .map(item -> new EvaluateScoringModelUseCase.SweepParameter(
                                item.getParameter(), item.getStep().doubleValue()))
                        .toList();
        return new EvaluateScoringModelUseCase.Query(
                engine(request.getEngine()),
                factions,
                request.getPlayerCounts().stream().sorted().toList(),
                rates,
                request.getGamesPerPlayerCount(),
                request.getSeed(),
                sweep);
    }

    private static SeatEraRates rates(
            io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SeatEraRates item) {
        var betReasons = item.getBetReasons().stream()
                .map(ScoringModelApiMapper::betReason)
                .toList();
        var others = item.getOtherReasons() == null
                ? List.<SeatEraRates.OtherReason>of()
                : item.getOtherReasons().stream()
                        .map(ScoringModelApiMapper::otherReason)
                        .toList();
        return new SeatEraRates(
                item.getPlayerCount(),
                faction(item.getFaction()),
                betReasons,
                item.getVoidedRate().doubleValue(),
                others);
    }

    private static SeatEraRates.BetReasonRate betReason(SeatEraRatesBetReasonsInner item) {
        return new SeatEraRates.BetReasonRate(
                item.getScoreReason(), item.getRate().doubleValue());
    }

    private static SeatEraRates.OtherReason otherReason(SeatEraRatesOtherReasonsInner item) {
        return new SeatEraRates.OtherReason(
                item.getScoreReason(), item.getPerEra().doubleValue());
    }

    static ScoringModelEvaluation evaluation(ScoringModelResult result) {
        return new ScoringModelEvaluation()
                .cells(result.evaluation().cells().stream()
                        .map(ScoringModelApiMapper::cell)
                        .toList())
                .sensitivity(result.sensitivity().stream()
                        .map(item -> new ScoringSensitivity()
                                .parameter(item.parameter())
                                .delta(decimal(item.delta()))
                                .playerCount(item.playerCount())
                                .faction(faction(item.faction()))
                                .ratioChange(decimal(item.ratioChange())))
                        .toList());
    }

    private static ScoringCell cell(
            io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEvaluation.Cell cell) {
        return new ScoringCell()
                .playerCount(cell.playerCount())
                .faction(faction(cell.faction()))
                .ratio(decimal(cell.ratio()))
                .lower(decimal(cell.lower()))
                .upper(decimal(cell.upper()));
    }

    private static io.github.temporalrift.workbench.shared.game.Faction faction(Faction faction) {
        return io.github.temporalrift.workbench.shared.game.Faction.valueOf(faction.getValue());
    }

    private static Faction faction(io.github.temporalrift.workbench.shared.game.Faction faction) {
        return Faction.fromValue(faction.name());
    }

    private static BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value);
    }
}
