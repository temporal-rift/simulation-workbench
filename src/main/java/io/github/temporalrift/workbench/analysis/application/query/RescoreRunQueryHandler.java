package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.fact.RecordedGames;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.analysis.domain.port.out.VariantScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.Rescorer;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringCalculator;
import io.github.temporalrift.workbench.analysis.domain.scoring.RescoringResult;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * Reads each succeeded case of the requested variant and table from its retained evidence, one at a time, and keeps
 * only what the case's verdict and the ratios need, so the cost in memory does not grow with the number of cases.
 */
public class RescoreRunQueryHandler implements RescoreRunUseCase {

    private static final String ENGINE_FACTIONS = "engine.factions";

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
        var cases = runs.cases(query.runId());
        if (cases.stream().noneMatch(item -> query.variantLabel().equals(item.variantLabel()))) {
            throw new AnalysisResourceNotFoundException("Variant", query.variantLabel());
        }
        if (cases.stream().noneMatch(item -> query.tableId().equals(item.tableId()))) {
            throw new AnalysisResourceNotFoundException("Table", query.tableId());
        }
        var rules = scoring.rules(experimentId, query.variantLabel())
                .orElseThrow(() -> new AnalysisResourceNotFoundException("Variant", query.variantLabel()));
        ScoringModelValidator.checkEngine(query.engine());
        ScoringModelValidator.checkOtherReasonPoints(query.otherReasonPoints(), rules.scoreDeltas());
        var members = cases.stream()
                .filter(AnalyzedCase::succeeded)
                .filter(item -> query.variantLabel().equals(item.variantLabel()))
                .filter(item -> query.tableId().equals(item.tableId()))
                .toList();
        checkFactions(query, members);
        var rescorer = new Rescorer(query.engine());
        var overrides = overridden(query.otherReasonPoints());
        var judgements = new ArrayList<RescoringCalculator.Judgement>();
        for (var member : members) {
            runs.countingGame(query.runId(), member).ifPresent(evidence -> {
                var game = RecordedGames.of(member, evidence, rules.scoreDeltas(), overrides);
                judgements.add(new RescoringCalculator.Judgement(
                        member.playerCount(),
                        member.seed(),
                        member.seatFactions(),
                        rescorer.judge(game, rules.maxEras())));
            });
        }
        var complete = cases.stream().allMatch(AnalyzedCase::finished);
        var key = query.runId() + "/" + query.variantLabel() + "/" + query.tableId();
        return RescoringCalculator.calculate(complete, key, judgements);
    }

    /** A seat whose faction the engine does not score has no rule, so the whole request is refused. */
    private static void checkFactions(Query query, List<AnalyzedCase> members) {
        var scored = EnumSet.noneOf(Faction.class);
        query.engine().factions().forEach(faction -> scored.add(faction.faction()));
        for (var member : members) {
            for (var faction : member.seatFactions()) {
                if (!scored.contains(faction)) {
                    throw ScoringModelValidator.invalid(
                            ENGINE_FACTIONS, "the engine has no entry for " + faction + ", which a case seats");
                }
            }
        }
    }

    /** Points each named reason counts in place of its configured value, keyed by score reason. */
    private static Map<String, Integer> overridden(List<ReasonPoints> points) {
        var overrides = new HashMap<String, Integer>();
        for (var item : points) {
            overrides.put(item.scoreReason(), item.points());
        }
        return overrides;
    }
}
