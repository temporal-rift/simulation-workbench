package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.List;

import io.github.temporalrift.workbench.analysis.application.port.in.EvaluateScoringModelUseCase;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEvaluation;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEvaluator;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModel;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModelResult;
import io.github.temporalrift.workbench.analysis.domain.scoring.Sensitivity;

public class EvaluateScoringModelQueryHandler implements EvaluateScoringModelUseCase {

    private static final int MAX_SWEEP = 64;

    @Override
    public ScoringModelResult handle(Query query) {
        var games =
                query.gamesPerPlayerCount() == null ? ScoringModelValidator.DEFAULT_GAMES : query.gamesPerPlayerCount();
        var model = new ScoringModel(query.engine(), query.playerCounts(), query.rates(), games, query.seed());
        ScoringModelValidator.check(model);
        var sweep = query.sweep() == null ? List.<SweepParameter>of() : query.sweep();
        if (sweep.size() > MAX_SWEEP) {
            throw ScoringModelValidator.invalid("sweep", "sweep must hold at most " + MAX_SWEEP + " items");
        }
        var targets = new ArrayList<SweepTarget>();
        for (var index = 0; index < sweep.size(); index++) {
            targets.add(resolve("sweep[" + index + "]", sweep.get(index), model));
        }
        var base = ScoringEvaluator.evaluate(model);
        var sensitivity = new ArrayList<Sensitivity>();
        for (var index = 0; index < targets.size(); index++) {
            var step = sweep.get(index).step();
            for (var delta : new double[] {step, -step}) {
                var moved = ScoringEvaluator.evaluate(targets.get(index).moved(model, delta));
                sensitivity.addAll(changes(targets.get(index).path(), delta, base, moved));
            }
        }
        return new ScoringModelResult(base, sensitivity);
    }

    /** Resolves a sweep item and checks that the model stays valid when its value moves a step either way. */
    private static SweepTarget resolve(String path, SweepParameter parameter, ScoringModel model) {
        var target = SweepTarget.resolve(parameter.parameter(), model);
        if (target == null) {
            throw ScoringModelValidator.invalid(
                    path + ".parameter", "'" + parameter.parameter() + "' is not a sweepable value of the model");
        }
        var step = parameter.step();
        if (step == 0 || !Double.isFinite(step) || (target.whole() && step != Math.rint(step))) {
            throw ScoringModelValidator.invalid(
                    path + ".step",
                    target.whole() ? "the step must be a non-zero integer" : "the step must be a non-zero number");
        }
        for (var delta : new double[] {step, -step}) {
            try {
                ScoringModelValidator.check(target.moved(model, delta));
            } catch (InvalidScoringModelException e) {
                throw ScoringModelValidator.invalid(
                        path + ".step", "a step of " + delta + " leaves the allowed range: " + e.getMessage());
            }
        }
        return target;
    }

    private static List<Sensitivity> changes(
            String parameter, double delta, ScoringEvaluation base, ScoringEvaluation moved) {
        var changes = new ArrayList<Sensitivity>();
        for (var index = 0; index < base.cells().size(); index++) {
            var before = base.cells().get(index);
            var after = moved.cells().get(index);
            changes.add(new Sensitivity(
                    parameter, delta, before.playerCount(), before.faction(), after.ratio() - before.ratio()));
        }
        return changes;
    }
}
