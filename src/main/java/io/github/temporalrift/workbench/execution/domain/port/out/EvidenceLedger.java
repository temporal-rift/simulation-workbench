package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.PinnedEvidence;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;

/**
 * Durable, protected evidence of the games a scope played. A scope is a logical case or a reproduction of
 * it; within a scope, evidence is kept per game so a game the runner abandoned stays on record next to the
 * one that counted. Raw events are deduplicated by source and event identifier and tracked with per-source
 * offsets; no order across sources is ever invented.
 */
public interface EvidenceLedger {

    /** Retains the exact manifest the scope runs under. Repeating it for the same scope changes nothing. */
    void pin(UUID scopeId, String manifestDigest, String manifestJson, Instant now);

    /** Retains the accepted decision transcript and the semantic digest of the scope's finished game. */
    void seal(UUID scopeId, String transcript, String resultDigest, Instant now);

    /**
     * The pinned artifacts, each verified against its content address.
     *
     * @throws io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException when an
     *     artifact is missing or no longer matches its content address
     * @return empty when the scope has no sealed evidence
     */
    Optional<PinnedEvidence> pinned(UUID scopeId);

    /** Appends the step to the game's stream and returns its position. */
    int append(UUID scopeId, UUID gameId, UUID attemptId, StepRecord step);

    /** Resolves the seat's latest unacknowledged step in the window once accepted state is known. */
    void resolve(UUID scopeId, UUID gameId, int seatIndex, String windowKey, StepOutcome outcome);

    /** The game's steps in the order they were sent. */
    List<StepRecord> steps(UUID scopeId, UUID gameId);

    /**
     * Retains the raw event unless its source and event identifier were already retained.
     *
     * @return whether the event is new evidence
     */
    boolean record(UUID scopeId, UUID attemptId, ObservedEvent event);

    /** Moves the source partition's next offset forward; it never moves back. */
    void advance(UUID scopeId, UUID gameId, String source, int partition, long nextOffset);

    /** The game's retained events ordered by source, partition and offset. */
    List<ObservedEvent> events(UUID scopeId, UUID gameId);

    /** The next offset to read for each source partition of the game. */
    Map<SourcePartition, Long> offsets(UUID scopeId, UUID gameId);

    /** Forgets everything retained for the scope; used before a reproduction runs again after an interruption. */
    void purge(UUID scopeId);

    record SourcePartition(String source, int partition) {}
}
