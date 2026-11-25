package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.FactionScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModelResult;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.FactionScoringEngine;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringCell;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringEnding;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringModelRequest;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ScoringSensitivity;

/** Maps the generated scoring-model request and response to and from the use case. */
final class ScoringModelApiMapper {

    private ScoringModelApiMapper() {}

    static EvaluateScoringModelUseCase.Query query(ScoringModelRequest request) {
        var engine = request.getEngine();
        var factions = new ArrayList<FactionScoring>();
        for (var index = 0; index < engine.getFactions().size(); index++) {
            factions.add(faction(index, engine.getFactions().get(index)));
        }
        var rates = request.getRates().stream()
                .map(item -> new BetRates(
                        item.getPlayerCount(),
                        faction(item.getFaction()),
                        item.getWinRate().doubleValue(),
                        item.getMissRate().doubleValue(),
                        item.getExtraPointsPerEra().doubleValue()))
                .toList();
        var sweep = request.getSweep() == null
                ? List.<EvaluateScoringModelUseCase.SweepParameter>of()
                : request.getSweep().stream()
                        .map(item -> new EvaluateScoringModelUseCase.SweepParameter(
                                item.getParameter(), item.getStep().doubleValue()))
                        .toList();
        return new EvaluateScoringModelUseCase.Query(
                new ScoringEngine(engine.getWinThreshold(), engine.getMaxEras(), factions),
                request.getPlayerCounts().stream().sorted().toList(),
                rates,
                request.getGamesPerPlayerCount(),
                request.getSeed(),
                sweep);
    }

    static ScoringModelEvaluation evaluation(ScoringModelResult result) {
        return new ScoringModelEvaluation()
                .cells(result.evaluation().cells().stream()
                        .map(cell -> new ScoringCell()
                                .playerCount(cell.playerCount())
                                .faction(faction(cell.faction()))
                                .ratio(decimal(cell.ratio()))
                                .lower(decimal(cell.lower()))
                                .upper(decimal(cell.upper())))
                        .toList())
                .endings(result.evaluation().endings().stream()
                        .map(ending -> new ScoringEnding()
                                .playerCount(ending.playerCount())
                                .meanEras(decimal(ending.meanEras()))
                                .thresholdShare(decimal(ending.thresholdShare()))
                                .finalEraShare(decimal(ending.finalEraShare())))
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

    private static FactionScoring faction(int index, FactionScoringEngine engine) {
        var path = "engine.factions[" + index + "]";
        if (engine.getObjectiveWins() != null && engine.getObjectiveBonus() == null) {
            throw new InvalidScoringModelException(
                    path + ".objectiveBonus", "objectiveBonus is required with objectiveWins");
        }
        var resetOn = engine.getResetOn().stream()
                .map(outcome -> BetOutcome.valueOf(outcome.getValue()))
                .collect(Collectors.toSet());
        return new FactionScoring(
                faction(engine.getFaction()),
                engine.getWinPoints(),
                engine.getMissPoints(),
                resetOn,
                engine.getObjectiveWins(),
                engine.getObjectiveBonus() == null ? 0 : engine.getObjectiveBonus());
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
