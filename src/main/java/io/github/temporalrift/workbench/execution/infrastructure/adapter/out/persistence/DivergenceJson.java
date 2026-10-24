package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.infrastructure.serialization.ObservationJson;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.StoredJson;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * A divergence as stored: its step, its kind and the two objects describing what was expected and what
 * happened, from which the matching variant is rebuilt.
 */
@Component
class DivergenceJson {

    private static final String SEAT_INDEX = "seatIndex";
    private static final String WINDOW = "window";
    private static final String DECISION = "decision";
    private static final String OBSERVATION = "observation";
    private static final String OUTCOME = "outcome";

    private final StoredJson json;

    DivergenceJson(StoredJson json) {
        this.json = json;
    }

    String write(Divergence divergence) {
        var values = new LinkedHashMap<String, Object>();
        values.put("step", divergence.step());
        values.put("kind", divergence.kind().name());
        values.put("expected", expected(divergence));
        values.put("actual", actual(divergence));
        return json.write(values);
    }

    Divergence read(String stored) {
        var values = json.read(stored, JsonNode.class);
        var step = values.get("step").intValue();
        var expected = values.get("expected");
        var actual = values.get("actual");
        return switch (Divergence.Kind.valueOf(values.get("kind").asString())) {
            case WINDOW -> new Divergence.Window(step, summary(expected), summary(actual));
            case OBSERVATION -> new Divergence.Observation(step, observed(expected), observed(actual));
            case DECISION -> new Divergence.Decision(step, summary(expected), summary(actual));
            case MISSING_STEP -> new Divergence.MissingStep(step, summary(expected));
            case UNEXPECTED_STEP -> new Divergence.UnexpectedStep(step, summary(actual));
            case END_REASON ->
                new Divergence.EndReason(
                        step,
                        EndReason.valueOf(expected.get("endReason").asString()),
                        EndReason.valueOf(actual.get("endReason").asString()));
            case WINNERS ->
                new Divergence.Winners(
                        step,
                        list(expected.get("winners"), Winner[].class),
                        list(actual.get("winners"), Winner[].class));
            case FINAL_SCORES ->
                new Divergence.FinalScores(
                        step,
                        list(expected.get("finalScores"), FinalScore[].class),
                        list(actual.get("finalScores"), FinalScore[].class));
            case ERAS ->
                new Divergence.Eras(
                        step,
                        expected.get("eras").intValue(),
                        actual.get("eras").intValue());
            case SEMANTIC_DIGEST ->
                new Divergence.SemanticDigest(
                        step,
                        expected.get("semanticDigest").asString(),
                        actual.get("semanticDigest").asString());
            case UNEXPECTED_WINDOW ->
                new Divergence.UnexpectedWindow(step, actual.get(SEAT_INDEX).intValue(), window(actual.get(WINDOW)));
            case COMMAND_REJECTED ->
                new Divergence.CommandRejected(
                        step,
                        expected.get(SEAT_INDEX).intValue(),
                        window(expected.get(WINDOW)),
                        CandidateCodec.decode(expected.get(DECISION).asString()),
                        List.of(json.read(actual.get("codes"), String[].class)));
        };
    }

    private Object expected(Divergence divergence) {
        return switch (divergence) {
            case Divergence.Window d -> step(d.expected());
            case Divergence.Observation d -> observed(d.expected());
            case Divergence.Decision d -> step(d.expected());
            case Divergence.MissingStep d -> step(d.expected());
            case Divergence.UnexpectedStep _ -> Map.of();
            case Divergence.EndReason d -> Map.of("endReason", d.expected());
            case Divergence.Winners d -> Map.of("winners", d.expected());
            case Divergence.FinalScores d -> Map.of("finalScores", d.expected());
            case Divergence.Eras d -> Map.of("eras", d.expected());
            case Divergence.SemanticDigest d -> Map.of("semanticDigest", d.expected());
            case Divergence.UnexpectedWindow d -> Map.of(SEAT_INDEX, d.seatIndex());
            case Divergence.CommandRejected d ->
                Map.of(
                        SEAT_INDEX,
                        d.seatIndex(),
                        WINDOW,
                        d.window(),
                        DECISION,
                        CandidateCodec.encode(d.refused()),
                        OUTCOME,
                        "ACCEPTED");
        };
    }

    private Object actual(Divergence divergence) {
        return switch (divergence) {
            case Divergence.Window d -> step(d.actual());
            case Divergence.Observation d -> observed(d.actual());
            case Divergence.Decision d -> step(d.actual());
            case Divergence.MissingStep _ -> Map.of();
            case Divergence.UnexpectedStep d -> step(d.actual());
            case Divergence.EndReason d -> Map.of("endReason", d.actual());
            case Divergence.Winners d -> Map.of("winners", d.actual());
            case Divergence.FinalScores d -> Map.of("finalScores", d.actual());
            case Divergence.Eras d -> Map.of("eras", d.actual());
            case Divergence.SemanticDigest d -> Map.of("semanticDigest", d.actual());
            case Divergence.UnexpectedWindow d -> unexpectedWindow(d);
            case Divergence.CommandRejected d -> Map.of(OUTCOME, "REJECTED", "codes", d.codes());
        };
    }

    private static Map<String, Object> unexpectedWindow(Divergence.UnexpectedWindow divergence) {
        var values = new LinkedHashMap<String, Object>();
        values.put(SEAT_INDEX, divergence.seatIndex());
        values.put(WINDOW, divergence.window());
        return values;
    }

    private static Map<String, Object> step(Divergence.StepSummary step) {
        return Map.of(
                SEAT_INDEX, step.seatIndex(), WINDOW, step.window(), DECISION, CandidateCodec.encode(step.decision()));
    }

    private static Map<String, Object> observed(Divergence.ObservedStep step) {
        return Map.of(
                SEAT_INDEX,
                step.seatIndex(),
                WINDOW,
                step.window(),
                OBSERVATION,
                ObservationJson.of(step.observation()));
    }

    private Divergence.StepSummary summary(JsonNode node) {
        Candidate decision = CandidateCodec.decode(node.get(DECISION).asString());
        return new Divergence.StepSummary(node.get(SEAT_INDEX).intValue(), window(node.get(WINDOW)), decision);
    }

    private Divergence.ObservedStep observed(JsonNode node) {
        return new Divergence.ObservedStep(
                node.get(SEAT_INDEX).intValue(),
                window(node.get(WINDOW)),
                ObservationJson.parse(node.get(OBSERVATION).asString()));
    }

    private WindowRef window(JsonNode node) {
        return node == null || node.isNull() ? null : json.read(node, WindowRef.class);
    }

    private <T> List<T> list(JsonNode node, Class<T[]> type) {
        return List.of(json.read(node, type));
    }
}
