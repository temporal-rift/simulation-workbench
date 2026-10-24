package io.github.temporalrift.workbench.execution.application.command;

import java.util.List;
import java.util.Optional;

import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;

/**
 * Finds the first place a reproduction differs from the saved case. Only accepted commands take part:
 * rejected and unacknowledged attempts are transport history that does not change what the game
 * accepted. Steps are compared in the order the seats sent them, then the authoritative ending.
 */
final class DivergenceFinder {

    private DivergenceFinder() {}

    static Optional<Divergence> first(
            List<StepRecord> original, List<StepRecord> reproduced, CaseResult expected, CaseResult actual) {
        var saved = original.stream().filter(StepRecord::isAccepted).toList();
        var again = reproduced.stream().filter(StepRecord::isAccepted).toList();
        var shared = Math.min(saved.size(), again.size());
        for (var i = 0; i < shared; i++) {
            var divergence = compare(saved.get(i), again.get(i));
            if (divergence.isPresent()) {
                return divergence;
            }
        }
        if (saved.size() > shared) {
            return Optional.of(new Divergence.MissingStep(saved.get(shared).step(), summary(saved.get(shared))));
        }
        if (again.size() > shared) {
            return Optional.of(new Divergence.UnexpectedStep(again.get(shared).step(), summary(again.get(shared))));
        }
        var endingStep = saved.isEmpty() ? 0 : saved.getLast().step() + 1;
        return ending(endingStep, expected, actual);
    }

    private static Optional<Divergence> compare(StepRecord saved, StepRecord again) {
        if (saved.seatIndex() != again.seatIndex() || !saved.window().equals(again.window())) {
            return Optional.of(new Divergence.Window(saved.step(), summary(saved), summary(again)));
        }
        if (!saved.observation().equals(again.observation())) {
            return Optional.of(new Divergence.Observation(saved.step(), observed(saved), observed(again)));
        }
        if (!saved.decision().equals(again.decision())) {
            return Optional.of(new Divergence.Decision(saved.step(), summary(saved), summary(again)));
        }
        return Optional.empty();
    }

    private static Optional<Divergence> ending(int step, CaseResult expected, CaseResult actual) {
        if (expected.endReason() != actual.endReason()) {
            return Optional.of(new Divergence.EndReason(step, expected.endReason(), actual.endReason()));
        }
        if (!expected.winners().equals(actual.winners())) {
            return Optional.of(new Divergence.Winners(step, expected.winners(), actual.winners()));
        }
        if (!expected.finalScores().equals(actual.finalScores())) {
            return Optional.of(new Divergence.FinalScores(step, expected.finalScores(), actual.finalScores()));
        }
        if (expected.eras() != actual.eras()) {
            return Optional.of(new Divergence.Eras(step, expected.eras(), actual.eras()));
        }
        if (!expected.semanticDigest().equals(actual.semanticDigest())) {
            return Optional.of(new Divergence.SemanticDigest(step, expected.semanticDigest(), actual.semanticDigest()));
        }
        return Optional.empty();
    }

    private static Divergence.StepSummary summary(StepRecord step) {
        return new Divergence.StepSummary(step.seatIndex(), step.window(), step.decision());
    }

    private static Divergence.ObservedStep observed(StepRecord step) {
        return new Divergence.ObservedStep(step.seatIndex(), step.window(), step.observation());
    }
}
