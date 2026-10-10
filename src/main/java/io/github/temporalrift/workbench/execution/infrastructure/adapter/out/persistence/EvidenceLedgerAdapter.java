package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.PinnedEvidence;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/**
 * Evidence: pinned artifacts in a content-addressed store whose digests are re-checked on every read, per-game
 * step streams, and raw events deduplicated by source and event identifier.
 */
@Component
public class EvidenceLedgerAdapter implements EvidenceLedger {

    private static final String JSON = "application/json";
    private static final String TEXT = "text/plain";
    private static final String UNACKNOWLEDGED = "UNACKNOWLEDGED";

    /** Sources are topic names, so their natural order is the byte order the ledger always listed them in. */
    private static final Comparator<EvidenceEventJpaEntity> EVENT_ORDER = Comparator.comparing(
                    EvidenceEventJpaEntity::source)
            .thenComparingInt(EvidenceEventJpaEntity::partitionNo)
            .thenComparingLong(EvidenceEventJpaEntity::offsetNo);

    private final EvidenceArtifactJpaRepository artifacts;
    private final CaseEvidenceJpaRepository evidence;
    private final EvidenceStepJpaRepository steps;
    private final EvidenceEventJpaRepository events;
    private final EvidenceSourceOffsetJpaRepository offsets;
    private final InsertOnce insertOnce;
    private final EvidenceUpdates updates;
    private final Clock clock;

    EvidenceLedgerAdapter(
            EvidenceArtifactJpaRepository artifacts,
            CaseEvidenceJpaRepository evidence,
            EvidenceStepJpaRepository steps,
            EvidenceEventJpaRepository events,
            EvidenceSourceOffsetJpaRepository offsets,
            InsertOnce insertOnce,
            EvidenceUpdates updates,
            Clock clock) {
        this.artifacts = artifacts;
        this.evidence = evidence;
        this.steps = steps;
        this.events = events;
        this.offsets = offsets;
        this.insertOnce = insertOnce;
        this.updates = updates;
        this.clock = clock;
    }

    @Override
    public void pin(UUID scopeId, String manifestDigest, String manifestJson, Instant now) {
        var artifact = putArtifact(manifestJson.getBytes(StandardCharsets.UTF_8), JSON, now);
        if (!evidence.existsById(scopeId)) {
            insertOnce.insert(
                    () -> evidence.saveAndFlush(new CaseEvidenceJpaEntity(scopeId, manifestDigest, artifact, now)));
        }
    }

    @Override
    public void seal(UUID scopeId, String transcript, String resultDigest, Instant now) {
        updates.seal(scopeId, putArtifact(transcript.getBytes(StandardCharsets.UTF_8), TEXT, now), resultDigest, now);
    }

    @Override
    public Optional<PinnedEvidence> pinned(UUID scopeId) {
        return evidence.findByScopeIdAndSealedAtIsNotNull(scopeId)
                .map(row -> new PinnedEvidence(
                        row.manifestDigest(),
                        artifactText(row.manifestArtifact()),
                        artifactText(row.transcriptArtifact()),
                        row.resultDigest()));
    }

    @Override
    @Transactional
    public int append(UUID scopeId, UUID gameId, UUID attemptId, StepRecord step) {
        var next = steps.findFirstByScopeIdAndGameIdOrderByStepDesc(scopeId, gameId)
                .map(latest -> latest.step() + 1)
                .orElse(0);
        steps.saveAndFlush(new EvidenceStepJpaEntity(scopeId, gameId, next, attemptId, step, clock.instant()));
        return next;
    }

    @Override
    @Transactional
    public void resolve(UUID scopeId, UUID gameId, int seatIndex, String windowKey, StepOutcome outcome, String code) {
        steps.findAllWithLockByScopeIdAndGameIdAndSeatIndexAndWindowKeyAndOutcome(
                        scopeId, gameId, seatIndex, windowKey, UNACKNOWLEDGED)
                .forEach(step -> step.resolve(outcome.name(), code));
    }

    @Override
    public List<StepRecord> steps(UUID scopeId, UUID gameId) {
        return steps.findAllByScopeIdAndGameIdOrderByStep(scopeId, gameId).stream()
                .map(EvidenceLedgerAdapter::stepRecord)
                .toList();
    }

    @Override
    public boolean retain(UUID scopeId, UUID attemptId, ObservedEvent event) {
        var key = new EvidenceEventJpaEntity.Key(scopeId, event.gameId(), event.source(), event.eventId());
        if (events.existsById(key)) {
            return false;
        }
        var now = clock.instant();
        var payload = putArtifact(event.payload().getBytes(StandardCharsets.UTF_8), JSON, now);
        return insertOnce.insert(
                () -> events.saveAndFlush(new EvidenceEventJpaEntity(scopeId, attemptId, event, payload, now)));
    }

    @Override
    public void advance(UUID scopeId, UUID gameId, String source, int partition, long nextOffset) {
        var key = new EvidenceSourceOffsetJpaEntity.Key(scopeId, gameId, source, partition);
        if (!offsets.existsById(key)) {
            insertOnce.insert(() -> offsets.saveAndFlush(
                    new EvidenceSourceOffsetJpaEntity(scopeId, gameId, source, partition, nextOffset)));
        }
        updates.raiseOffset(scopeId, gameId, source, partition, nextOffset);
    }

    @Override
    public List<ObservedEvent> events(UUID scopeId, UUID gameId) {
        var retained = events.findAllByScopeIdAndGameId(scopeId, gameId).stream()
                .sorted(EVENT_ORDER)
                .toList();
        var payloads = artifacts
                .findAllById(retained.stream()
                        .map(EvidenceEventJpaEntity::payloadArtifact)
                        .distinct()
                        .toList())
                .stream()
                .collect(Collectors.toMap(EvidenceArtifactJpaEntity::getId, Function.identity()));
        return retained.stream()
                .map(event -> observed(
                        event,
                        verified(
                                event.payloadArtifact(),
                                Optional.ofNullable(payloads.get(event.payloadArtifact()))
                                        .map(EvidenceArtifactJpaEntity::content)
                                        .orElse(null))))
                .toList();
    }

    @Override
    public Map<SourcePartition, Long> offsets(UUID scopeId, UUID gameId) {
        var result = new LinkedHashMap<SourcePartition, Long>();
        offsets.findAllByScopeIdAndGameId(scopeId, gameId).stream()
                .sorted(Comparator.comparing(EvidenceSourceOffsetJpaEntity::source)
                        .thenComparingInt(EvidenceSourceOffsetJpaEntity::partitionNo))
                .forEach(offset ->
                        result.put(new SourcePartition(offset.source(), offset.partitionNo()), offset.nextOffset()));
        return result;
    }

    @Override
    @Transactional
    public void purge(UUID scopeId) {
        steps.deleteAllByScopeId(scopeId);
        events.deleteAllByScopeId(scopeId);
        offsets.deleteAllByScopeId(scopeId);
        evidence.deleteById(scopeId);
    }

    private String putArtifact(byte[] content, String mediaType, Instant now) {
        var digest = sha256(content);
        if (!artifacts.existsById(digest)) {
            insertOnce.insert(
                    () -> artifacts.saveAndFlush(new EvidenceArtifactJpaEntity(digest, mediaType, content, now)));
        }
        return digest;
    }

    /** Reads an artifact and refuses content that no longer hashes to its address. */
    private String artifactText(String digest) {
        if (digest == null) {
            throw new ManifestMismatchException("A pinned artifact is missing");
        }
        return verified(
                digest,
                artifacts
                        .findById(digest)
                        .map(EvidenceArtifactJpaEntity::content)
                        .orElse(null));
    }

    /** Refuses content that is missing or no longer hashes to its address. */
    private static String verified(String digest, byte[] content) {
        if (content == null) {
            throw new ManifestMismatchException("Pinned artifact " + digest + " is unavailable");
        }
        if (!sha256(content).equals(digest)) {
            throw new ManifestMismatchException("Pinned artifact " + digest + " does not match its content address");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static StepRecord stepRecord(EvidenceStepJpaEntity step) {
        return new StepRecord(
                step.step(),
                step.seatIndex(),
                step.windowKey(),
                step.phase(),
                step.era(),
                step.round(),
                step.logicalTime(),
                step.observation(),
                step.decision(),
                StepOutcome.valueOf(step.outcome()),
                step.outcomeCode(),
                step.entropy());
    }

    private static ObservedEvent observed(EvidenceEventJpaEntity event, String payload) {
        return new ObservedEvent(
                event.source(),
                event.partitionNo(),
                event.offsetNo(),
                event.eventId(),
                event.eventType(),
                event.aggregateId(),
                event.aggregateType(),
                event.gameId(),
                event.occurredAt(),
                event.version() == null ? 0 : event.version(),
                payload);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
