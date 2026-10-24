package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.infrastructure.serialization.ObservationJson;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/** The published {@code {step, kind, expected, actual}} shape of a divergence. */
final class DivergenceWire {

    private static final String SEAT_INDEX = "seatIndex";
    private static final String WINDOW = "window";
    private static final String DECISION = "decision";
    private static final String OUTCOME = "outcome";
    private static final String UNKNOWN_WINDOW = "unknown";

    private DivergenceWire() {}

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Divergence toApi(
            Divergence divergence) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Divergence(
                divergence.step(), divergence.kind().name(), expected(divergence), actual(divergence));
    }

    private static Map<String, Object> expected(Divergence divergence) {
        return switch (divergence) {
            case Divergence.Window d -> step(d.expected());
            case Divergence.Observation d -> observed(d.expected());
            case Divergence.Decision d -> step(d.expected());
            case Divergence.MissingStep d -> step(d.expected());
            case Divergence.UnexpectedStep _ -> Map.of();
            case Divergence.EndReason d -> Map.of("endReason", d.expected().name());
            case Divergence.Winners d -> Map.of("winners", d.expected());
            case Divergence.FinalScores d -> Map.of("finalScores", d.expected());
            case Divergence.Eras d -> Map.of("eras", d.expected());
            case Divergence.SemanticDigest d -> Map.of("semanticDigest", d.expected());
            case Divergence.UnexpectedWindow d ->
                Map.of(SEAT_INDEX, d.seatIndex(), DECISION, "none retained in the transcript");
            case Divergence.CommandRejected d ->
                Map.of(
                        SEAT_INDEX,
                        d.seatIndex(),
                        WINDOW,
                        label(d.window()),
                        DECISION,
                        CandidateCodec.encode(d.refused()),
                        OUTCOME,
                        "ACCEPTED");
        };
    }

    private static Map<String, Object> actual(Divergence divergence) {
        return switch (divergence) {
            case Divergence.Window d -> step(d.actual());
            case Divergence.Observation d -> observed(d.actual());
            case Divergence.Decision d -> step(d.actual());
            case Divergence.MissingStep _ -> Map.of();
            case Divergence.UnexpectedStep d -> step(d.actual());
            case Divergence.EndReason d -> Map.of("endReason", d.actual().name());
            case Divergence.Winners d -> Map.of("winners", d.actual());
            case Divergence.FinalScores d -> Map.of("finalScores", d.actual());
            case Divergence.Eras d -> Map.of("eras", d.actual());
            case Divergence.SemanticDigest d -> Map.of("semanticDigest", d.actual());
            case Divergence.UnexpectedWindow d ->
                Map.of(SEAT_INDEX, d.seatIndex(), WINDOW, d.window() == null ? UNKNOWN_WINDOW : label(d.window()));
            case Divergence.CommandRejected d ->
                Map.of(SEAT_INDEX, d.seatIndex(), WINDOW, label(d.window()), OUTCOME, "REJECTED", "codes", d.codes());
        };
    }

    private static Map<String, Object> step(Divergence.StepSummary step) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put(SEAT_INDEX, step.seatIndex());
        summary.put(WINDOW, label(step.window()));
        summary.put(DECISION, CandidateCodec.encode(step.decision()));
        return summary;
    }

    private static Map<String, Object> observed(Divergence.ObservedStep step) {
        var detail = new LinkedHashMap<String, Object>();
        detail.put(SEAT_INDEX, step.seatIndex());
        detail.put(WINDOW, label(step.window()));
        detail.put("observation", ObservationJson.toMap(step.observation()));
        return detail;
    }

    private static String label(WindowRef window) {
        return window.label();
    }
}
