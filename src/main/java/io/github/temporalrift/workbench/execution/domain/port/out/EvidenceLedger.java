package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.PinnedEvidence;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.shared.window.WindowRef;

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

    /** Appends the step to the game's stream, before its command is sent, and returns its position. */
    int append(UUID scopeId, UUID gameId, UUID attemptId, StepRecord step);

    /**
     * Resolves the seat's unacknowledged step in the window once the answer is known.
     *
     * @param code the refusal code of a rejected command, otherwise null
     */
    void resolve(UUID scopeId, UUID gameId, int seatIndex, WindowRef window, StepOutcome outcome, String code);

    /** The game's steps in the order they were sent. */
    List<StepRecord> steps(UUID scopeId, UUID gameId);

    /**
     * The game's steps after the given step number, in the order they were sent, at most {@code limit} of them.
     *
     * @param seatIndex the only seat whose steps are listed; null for every seat
     * @param afterStep the last step number the reader already has; steps up to and including it are skipped
     */
    List<StepRecord> stepsPage(UUID scopeId, UUID gameId, Integer seatIndex, int afterStep, int limit);

    /** The highest step number the game has, or empty when it has no step. */
    OptionalInt lastStep(UUID scopeId, UUID gameId);

    /**
     * The manifest digest the scope was pinned under, whether or not its evidence is sealed yet.
     *
     * @return empty when the scope was never pinned
     */
    Optional<String> manifestDigest(UUID scopeId);

    /**
     * Retains the raw event unless its source and event identifier were already retained.
     *
     * @return whether the event is new evidence
     */
    boolean retain(UUID scopeId, UUID attemptId, ObservedEvent event);

    /**
     * Retains the raw events in one step, each unless its source and event identifier were already retained, so a
     * batch repeated in whole or in part changes nothing.
     */
    void retainAll(UUID scopeId, UUID attemptId, List<ObservedEvent> events);

    /** Moves the source partition's next offset forward; it never moves back. */
    void advance(UUID scopeId, UUID gameId, String source, int partition, long nextOffset);

    /** Moves the next offset of every given source partition forward in one step; none ever moves back. */
    void advanceAll(UUID scopeId, UUID gameId, Map<SourcePartition, Long> nextOffsets);

    /** The game's retained events ordered by source, partition and offset. */
    List<ObservedEvent> events(UUID scopeId, UUID gameId);

    /**
     * A window of {@link #events}: skips {@code offset} events, then returns at most {@code limit} of them. Only the
     * payloads of the returned events are read and verified; the lookahead is answered from the row alone.
     */
    EventPage eventsPage(UUID scopeId, UUID gameId, int offset, int limit);

    /**
     * The first retained event of the type in {@link #events} order, with only its payload verified.
     *
     * @return empty when no event of the type was retained
     */
    Optional<ObservedEvent> firstEventOfType(UUID scopeId, UUID gameId, String eventType);

    /** The next offset to read for each source partition of the game. */
    Map<SourcePartition, Long> offsets(UUID scopeId, UUID gameId);

    /** Forgets everything retained for the scope; used before a reproduction runs again after an interruption. */
    void purge(UUID scopeId);

    /**
     * @param events the requested window of events, with their payloads
     * @param more whether the game retains an event after the window
     */
    record EventPage(List<ObservedEvent> events, boolean more) {
        public EventPage {
            events = List.copyOf(events);
        }
    }

    record SourcePartition(String source, int partition) {}
}
