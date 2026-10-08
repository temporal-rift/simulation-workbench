package io.github.temporalrift.workbench.execution.infrastructure.adapter.in.rest;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Replay;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReplayPerspective;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReplayStep;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.ReproductionState;

/** Maps retained evidence and reproductions to the published {@code simulation-api} representations. */
final class ReplayApiMapper {

    private static final ObjectMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

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
                divergence(reproduction.firstDivergence()));
    }

    private static ReplayStep step(GetCaseReplayUseCase.Entry entry) {
        return switch (entry) {
            case GetCaseReplayUseCase.Entry.Command command -> commandStep(command.step(), command.record());
            case GetCaseReplayUseCase.Entry.Event event -> eventStep(event.step(), event.event());
        };
    }

    private static ReplayStep commandStep(int step, StepRecord record) {
        var decision = new LinkedHashMap<String, Object>();
        decision.put("seatIndex", record.seatIndex());
        decision.put("window", record.windowKey());
        decision.put("candidate", record.decision());
        decision.put("entropy", record.entropy() == null ? null : parse(record.entropy()));
        var result = new LinkedHashMap<String, Object>();
        result.put("outcome", record.outcome().name());
        if (record.outcomeCode() != null) {
            result.put("code", record.outcomeCode());
        }
        return new ReplayStep(
                step,
                record.era(),
                record.round(),
                record.phase(),
                utc(record.logicalTime()),
                parse(record.observation()),
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

    private static io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Divergence
            divergence(Divergence divergence) {
        return divergence == null
                ? null
                : new io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Divergence(
                        divergence.step(), divergence.kind(), divergence.expected(), divergence.actual());
    }

    private static Map<String, Object> parse(String json) {
        try {
            return JSON.readValue(json, OBJECT);
        } catch (JacksonException e) {
            throw new IllegalStateException("Retained evidence is not valid JSON", e);
        }
    }

    private static Object parseAny(String json) {
        try {
            return JSON.readValue(json, Object.class);
        } catch (JacksonException _) {
            return json;
        }
    }

    private static OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
