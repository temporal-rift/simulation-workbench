package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepEntropy;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.infrastructure.serialization.ObservationJson;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Replay;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReplayPerspective;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReplayStep;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReproductionState;
import io.github.temporalrift.workbench.shared.text.JsonText;

/** Maps retained evidence and reproductions to the published {@code simulation-api} representations. */
final class ReplayApiMapper {

    private ReplayApiMapper() {}

    static Replay toApi(GetCaseReplayUseCase.ReplayPage page) {
        return new Replay(
                page.caseId(),
                page.manifestDigest(),
                ReplayPerspective.valueOf(page.perspective().name()),
                page.entries().stream().map(ReplayApiMapper::step).toList(),
                page.nextStep());
    }

    static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Reproduction toApi(
            Reproduction reproduction) {
        return new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Reproduction(
                reproduction.reproductionId(),
                reproduction.attemptId(),
                ReproductionState.valueOf(reproduction.state().name()),
                reproduction.firstDivergence() == null ? null : DivergenceWire.toApi(reproduction.firstDivergence()));
    }

    private static ReplayStep step(GetCaseReplayUseCase.Entry entry) {
        return switch (entry) {
            case GetCaseReplayUseCase.Entry.Command(var number, var command) -> commandStep(number, command);
            case GetCaseReplayUseCase.Entry.Event(var number, var event) -> eventStep(number, event);
        };
    }

    private static ReplayStep commandStep(int number, StepRecord step) {
        var decision = new LinkedHashMap<String, Object>();
        decision.put("seatIndex", step.seatIndex());
        decision.put("window", step.window().label());
        decision.put("candidate", CandidateCodec.encode(step.decision()));
        decision.put("entropy", step.entropy() == null ? null : entropy(step.entropy()));
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", step.outcome().name());
        if (step.outcomeCode() != null) {
            result.put("code", step.outcomeCode());
        }
        return new ReplayStep(
                number,
                step.window().era(),
                step.window().round(),
                step.window().kind().name(),
                utc(step.logicalTime()),
                ObservationJson.toMap(step.observation()),
                decision,
                result);
    }

    private static ReplayStep eventStep(int step, ObservedEvent event) {
        var evidence = new LinkedHashMap<String, Object>();
        evidence.put("source", event.source());
        evidence.put("partition", event.partition());
        evidence.put("offset", event.offset());
        evidence.put("eventId", event.eventId());
        evidence.put("eventType", event.eventType());
        evidence.put("aggregateId", event.aggregateId());
        evidence.put("aggregateType", event.aggregateType());
        evidence.put("gameId", event.gameId());
        evidence.put("occurredAt", event.occurredAt());
        evidence.put("version", event.version());
        evidence.put("payload", parseAny(event.payload()));
        return new ReplayStep(
                step,
                null,
                null,
                "EVENT",
                utc(event.occurredAt() == null ? Instant.EPOCH : event.occurredAt()),
                evidence,
                null,
                null);
    }

    private static Map<String, Object> entropy(StepEntropy entropy) {
        var coordinates = new LinkedHashMap<String, Object>();
        coordinates.put("stream", entropy.stream());
        coordinates.put("policySeed", entropy.policySeed());
        coordinates.put("seat", entropy.seat());
        coordinates.put("window", entropy.window().label());
        coordinates.put("drawIndex", entropy.drawIndex());
        return coordinates;
    }

    private static Object parseAny(String json) {
        try {
            return JsonText.read(json, Object.class, "Retained evidence");
        } catch (IllegalStateException _) {
            return json;
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
