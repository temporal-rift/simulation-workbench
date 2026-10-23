package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.GameEndedParser;

/**
 * Retains the records the slot routed for one game as evidence, exactly as delivered to the services. It resumes from
 * the offsets it retained, and a drain reports the end of every source only once the slot has delivered everything it
 * was handed, so nothing published earlier is still on its way. It never feeds a policy.
 */
final class SimulatorEventObserver implements GameEventObserver {

    private static final int PARTITION = 0;
    private static final String[] TOPICS = {SlotEventRouter.GAME_EVENTS, SlotEventRouter.TIMELINE_EVENTS};

    private final SlotEventRouter router;
    private final EvidenceLedger evidence;
    private final ObjectMapper objectMapper;
    private final Target target;
    private final Map<String, Long> positions = new HashMap<>();
    private GameEnded gameEnded;

    /**
     * @param scopeId the logical case, or reproduction, the evidence belongs to
     * @param attemptId the attempt that retains it
     */
    record Target(UUID scopeId, UUID attemptId, UUID gameId) {}

    SimulatorEventObserver(SlotEventRouter router, EvidenceLedger evidence, ObjectMapper objectMapper, Target target) {
        this.router = router;
        this.evidence = evidence;
        this.objectMapper = objectMapper;
        this.target = target;
        var retained = evidence.offsets(target.scopeId(), target.gameId());
        for (var topic : TOPICS) {
            positions.put(topic, retained.getOrDefault(new EvidenceLedger.SourcePartition(topic, PARTITION), 0L));
        }
        evidence.firstEventOfType(target.scopeId(), target.gameId(), GameEndedParser.EVENT_TYPE)
                .ifPresent(event -> gameEnded = GameEndedParser.parse(objectMapper, event));
    }

    @Override
    public synchronized boolean drain() {
        var failure = router.failure();
        if (failure != null) {
            throw new AttemptFailedException(FailureCode.EXECUTION_FAILED, failure.getMessage(), failure);
        }
        // Idle is read before the records: everything published before it was true is delivered and retained here.
        var delivered = router.idle();
        var advanced = new HashMap<String, Long>();
        for (var topic : TOPICS) {
            var from = positions.get(topic);
            var records = router.routed(topic, from);
            retain(records.stream().filter(this::isThisGame).map(this::event).toList());
            if (!records.isEmpty()) {
                advanced.put(topic, from + records.size());
            }
        }
        if (!advanced.isEmpty()) {
            var offsets = new HashMap<EvidenceLedger.SourcePartition, Long>();
            advanced.forEach((topic, next) -> offsets.put(new EvidenceLedger.SourcePartition(topic, PARTITION), next));
            evidence.advanceAll(target.scopeId(), target.gameId(), offsets);
            positions.putAll(advanced);
        }
        return delivered;
    }

    @Override
    public synchronized Optional<GameEnded> gameEnded() {
        return Optional.ofNullable(gameEnded);
    }

    @Override
    public void close() {
        // The slot owns the routed records; there is nothing to release.
    }

    private boolean isThisGame(SlotEventRouter.Routed item) {
        var header = item.headers().get("gameId");
        return target.gameId().toString().equals(header != null ? header : item.key());
    }

    private void retain(List<ObservedEvent> events) {
        evidence.retainAll(target.scopeId(), target.attemptId(), events);
        events.stream()
                .filter(event -> GameEndedParser.EVENT_TYPE.equals(event.eventType()))
                .reduce((_, last) -> last)
                .ifPresent(event -> gameEnded = GameEndedParser.parse(objectMapper, event));
    }

    private ObservedEvent event(SlotEventRouter.Routed item) {
        var headers = item.headers();
        var eventId = headers.get("eventId");
        var eventType = headers.get("eventType");
        if (eventId == null || eventType == null) {
            throw new AttemptFailedException(
                    FailureCode.CONTRACT_MISMATCH,
                    "A record of " + item.topic() + " at offset " + item.offset()
                            + " carries no event identifier or type");
        }
        var occurredAt = headers.get("occurredAt");
        var aggregateId = headers.get("aggregateId");
        var version = headers.get("version");
        return new ObservedEvent(
                item.topic(),
                PARTITION,
                item.offset(),
                UUID.fromString(eventId),
                eventType,
                aggregateId == null ? null : UUID.fromString(aggregateId),
                headers.get("aggregateType"),
                target.gameId(),
                occurredAt == null ? null : Instant.parse(occurredAt),
                version == null ? 0 : Integer.parseInt(version),
                new String(item.payload(), StandardCharsets.UTF_8));
    }
}
