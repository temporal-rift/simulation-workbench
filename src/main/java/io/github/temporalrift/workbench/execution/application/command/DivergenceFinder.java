package io.github.temporalrift.workbench.execution.application.command;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;

/**
 * Finds the first place a reproduction differs from the saved case. Only accepted commands take part:
 * rejected and unacknowledged attempts are transport history that does not change what the game
 * accepted. Steps are compared in the order the seats sent them, then the authoritative ending.
 */
final class DivergenceFinder {

    private static final ObjectMapper JSON = JsonMapper.builder().build();

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
            return Optional.of(
                    new Divergence(saved.get(shared).step(), "MISSING_STEP", summary(saved.get(shared)), Map.of()));
        }
        if (again.size() > shared) {
            return Optional.of(
                    new Divergence(again.get(shared).step(), "UNEXPECTED_STEP", Map.of(), summary(again.get(shared))));
        }
        var endingStep = saved.isEmpty() ? 0 : saved.getLast().step() + 1;
        return ending(endingStep, expected, actual);
    }

    private static Optional<Divergence> compare(StepRecord saved, StepRecord again) {
        if (saved.seatIndex() != again.seatIndex() || !saved.windowKey().equals(again.windowKey())) {
            return Optional.of(new Divergence(saved.step(), "WINDOW", summary(saved), summary(again)));
        }
        if (!saved.observation().equals(again.observation())) {
            return Optional.of(new Divergence(
                    saved.step(),
                    "OBSERVATION",
                    detail(saved, "observation", parse(saved.observation())),
                    detail(again, "observation", parse(again.observation()))));
        }
        if (!saved.decision().equals(again.decision())) {
            return Optional.of(new Divergence(
                    saved.step(),
                    "DECISION",
                    detail(saved, "decision", saved.decision()),
                    detail(again, "decision", again.decision())));
        }
        return Optional.empty();
    }

    private static Optional<Divergence> ending(int step, CaseResult expected, CaseResult actual) {
        if (expected.endReason() != actual.endReason()) {
            return Optional.of(new Divergence(
                    step,
                    "END_REASON",
                    Map.of("endReason", expected.endReason().name()),
                    Map.of("endReason", actual.endReason().name())));
        }
        if (!expected.winners().equals(actual.winners())) {
            return Optional.of(new Divergence(
                    step, "WINNERS", Map.of("winners", expected.winners()), Map.of("winners", actual.winners())));
        }
        if (!expected.finalScores().equals(actual.finalScores())) {
            return Optional.of(new Divergence(
                    step,
                    "FINAL_SCORES",
                    Map.of("finalScores", expected.finalScores()),
                    Map.of("finalScores", actual.finalScores())));
        }
        if (expected.eras() != actual.eras()) {
            return Optional.of(
                    new Divergence(step, "ERAS", Map.of("eras", expected.eras()), Map.of("eras", actual.eras())));
        }
        if (!expected.semanticDigest().equals(actual.semanticDigest())) {
            return Optional.of(new Divergence(
                    step,
                    "SEMANTIC_DIGEST",
                    Map.of("semanticDigest", expected.semanticDigest()),
                    Map.of("semanticDigest", actual.semanticDigest())));
        }
        return Optional.empty();
    }

    private static Map<String, Object> summary(StepRecord step) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("seatIndex", step.seatIndex());
        summary.put("window", step.windowKey());
        summary.put("decision", step.decision());
        return summary;
    }

    private static Map<String, Object> detail(StepRecord step, String name, Object value) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put("seatIndex", step.seatIndex());
        detail.put("window", step.windowKey());
        detail.put(name, value);
        return detail;
    }

    private static Object parse(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (JacksonException e) {
            throw new IllegalStateException("A retained observation is not valid JSON", e);
        }
    }
}
