package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.List;

import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.fact.RecordedGames;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.analysis.domain.port.out.VariantScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.Rescorer;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringCalculator;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringResult;

/**
 * Reads retained evidence once per case and keeps verdicts for the base and requested neighbors.
 */
public class RescoreRunQueryHandler implements RescoreRunUseCase {

    private final RunSource runs;
    private final VariantScoring scoring;

    public RescoreRunQueryHandler(RunSource runs, VariantScoring scoring) {
        this.runs = runs;
        this.scoring = scoring;
    }

    @Override
    public RescoringResult handle(Query query) {
        var experimentId = runs.experimentOf(query.runId())
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Run", query.runId()));
        var rules = scoring.rules(experimentId, query.variantLabel())
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Variant", query.variantLabel()));
        var configured = rules.scoreDeltas().keySet();
        ScoringModelValidator.checkEngine(query.engine());
        ScoringModelValidator.checkCoverage(query.engine(), configured);
        var moves = RescoreSweep.resolve(query.engine(), query.sweep());
        var cases = runs.cases(query.runId());
        if (cases.stream().noneMatch(item -> query.variantLabel().equals(item.variantLabel()))) {
            throw new AnalysisResourceNotFoundException("Variant", query.variantLabel());
        }
        if (cases.stream().noneMatch(item -> query.tableId().equals(item.tableId()))) {
            throw new AnalysisResourceNotFoundException("Table", query.tableId());
        }
        var members = cases.stream()
                .filter(AnalyzedCase::succeeded)
                .filter(item -> query.variantLabel().equals(item.variantLabel()))
                .filter(item -> query.tableId().equals(item.tableId()))
                .toList();
        var rescorer = new Rescorer(query.engine());
        var judgements = new ArrayList<RescoringCalculator.Judgement>();
        var movedJudgements = moves.stream()
                .map(move -> new ArrayList<RescoringCalculator.Judgement>())
                .toList();
        var movedRescorers = moves.stream()
                .map(move -> move.engine() == null ? null : new Rescorer(move.engine()))
                .toList();
        for (var member : members) {
            runs.countingGame(query.runId(), member).ifPresent(evidence -> {
                var game = RecordedGames.of(member, evidence, configured);
                judgements.add(new RescoringCalculator.Judgement(
                        member.playerCount(),
                        member.seed(),
                        member.seatFactions(),
                        rescorer.judge(game, rules.maxEras())));
                for (int i = 0; i < moves.size(); i++) {
                    var neighbor = movedRescorers.get(i);
                    if (neighbor != null) {
                        movedJudgements
                                .get(i)
                                .add(new RescoringCalculator.Judgement(
                                        member.playerCount(),
                                        member.seed(),
                                        member.seatFactions(),
                                        neighbor.judge(game, rules.maxEras())));
                    }
                }
            });
        }
        var complete = cases.stream().allMatch(AnalyzedCase::finished);
        var key = query.runId() + "/" + query.variantLabel() + "/" + query.tableId();
        var result = RescoringCalculator.calculate(complete, key, judgements);
        if (query.sweep() == null) {
            return result;
        }
        var sensitivity = sensitivity(moves, movedJudgements, result);
        return new RescoringResult(complete, result.cells(), result.playerCounts(), sensitivity);
    }

    private static List<RescoringResult.Sensitivity> sensitivity(
            List<RescoreSweep.Move> moves,
            List<ArrayList<RescoringCalculator.Judgement>> movedJudgements,
            RescoringResult result) {
        var sensitivity = new ArrayList<RescoringResult.Sensitivity>();
        for (int i = 0; i < moves.size(); i++) {
            var move = moves.get(i);
            if (move.engine() == null) {
                sensitivity.add(new RescoringResult.Sensitivity(
                        move.parameter(), move.direction(), RescoringResult.Status.OUT_OF_RANGE, List.of()));
                continue;
            }
            var neighbor = RescoringCalculator.ratios(movedJudgements.get(i));
            var cells = new ArrayList<RescoringResult.SensitivityCell>();
            for (var base : result.cells()) {
                var cell = neighbor.stream()
                        .filter(c -> c.playerCount() == base.playerCount() && c.faction() == base.faction())
                        .findFirst()
                        .orElseThrow();
                cells.add(new RescoringResult.SensitivityCell(
                        base.playerCount(), base.faction(), cell.ratio(), cell.ratio() - base.ratio()));
            }
            sensitivity.add(new RescoringResult.Sensitivity(
                    move.parameter(), move.direction(), RescoringResult.Status.EVALUATED, cells));
        }
        return sensitivity;
    }
}
