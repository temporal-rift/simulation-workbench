package io.github.temporalrift.workbench.execution.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.PinnedEvidence;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.reproduction.ManifestMismatchException;

/** A map-backed evidence ledger with the same semantics as the PostgreSQL one, for tests without a database. */
public class InMemoryEvidenceLedger implements EvidenceLedger {

    private record Game(UUID scopeId, UUID gameId) {}

    private record Header(
            String manifestDigest, String manifestArtifact, String transcriptArtifact, String resultDigest) {}

    private final Map<String, byte[]> artifacts = new ConcurrentHashMap<>();
    private final Map<UUID, Header> headers = new ConcurrentHashMap<>();
    private final Map<Game, List<StepRecord>> steps = new ConcurrentHashMap<>();
    private final Map<Game, Map<String, ObservedEvent>> events = new ConcurrentHashMap<>();
    private final Map<Game, Map<SourcePartition, Long>> offsets = new ConcurrentHashMap<>();

    @Override
    public synchronized void pin(UUID scopeId, String manifestDigest, String manifestJson, Instant now) {
        var artifact = put(manifestJson);
        headers.putIfAbsent(scopeId, new Header(manifestDigest, artifact, null, null));
    }

    @Override
    public synchronized void seal(UUID scopeId, String transcript, String resultDigest, Instant now) {
        var header = headers.get(scopeId);
        if (header == null) {
            throw new IllegalStateException("Evidence of " + scopeId + " was sealed before it was pinned");
        }
        headers.put(
                scopeId, new Header(header.manifestDigest(), header.manifestArtifact(), put(transcript), resultDigest));
    }

    @Override
    public synchronized Optional<PinnedEvidence> pinned(UUID scopeId) {
        var header = headers.get(scopeId);
        if (header == null || header.transcriptArtifact() == null) {
            return Optional.empty();
        }
        return Optional.of(new PinnedEvidence(
                header.manifestDigest(),
                text(header.manifestArtifact()),
                text(header.transcriptArtifact()),
                header.resultDigest()));
    }

    @Override
    public synchronized int append(UUID scopeId, UUID gameId, UUID attemptId, StepRecord step) {
        var list = steps.computeIfAbsent(new Game(scopeId, gameId), key -> new ArrayList<>());
        list.add(step.numbered(list.size()));
        return list.size() - 1;
    }

    @Override
    public synchronized void resolve(UUID scopeId, UUID gameId, int seatIndex, String windowKey, StepOutcome outcome) {
        var list = steps.getOrDefault(new Game(scopeId, gameId), List.of());
        for (var index = 0; index < list.size(); index++) {
            var step = list.get(index);
            if (step.seatIndex() == seatIndex
                    && step.windowKey().equals(windowKey)
                    && step.outcome() == StepOutcome.UNACKNOWLEDGED) {
                list.set(index, step.withOutcome(outcome, step.outcomeCode()));
            }
        }
    }

    @Override
    public synchronized List<StepRecord> steps(UUID scopeId, UUID gameId) {
        return List.copyOf(steps.getOrDefault(new Game(scopeId, gameId), List.of()));
    }

    @Override
    public synchronized boolean record(UUID scopeId, UUID attemptId, ObservedEvent event) {
        var game = events.computeIfAbsent(new Game(scopeId, event.gameId()), key -> new LinkedHashMap<>());
        return game.putIfAbsent(event.source() + "|" + event.eventId(), event) == null;
    }

    @Override
    public synchronized void advance(UUID scopeId, UUID gameId, String source, int partition, long nextOffset) {
        offsets.computeIfAbsent(new Game(scopeId, gameId), key -> new ConcurrentHashMap<>())
                .merge(new SourcePartition(source, partition), nextOffset, Math::max);
    }

    @Override
    public synchronized List<ObservedEvent> events(UUID scopeId, UUID gameId) {
        return events.getOrDefault(new Game(scopeId, gameId), Map.of()).values().stream()
                .sorted(Comparator.comparing(ObservedEvent::source)
                        .thenComparingInt(ObservedEvent::partition)
                        .thenComparingLong(ObservedEvent::offset))
                .toList();
    }

    @Override
    public synchronized Map<SourcePartition, Long> offsets(UUID scopeId, UUID gameId) {
        var sorted = new TreeMap<SourcePartition, Long>(
                Comparator.comparing(SourcePartition::source).thenComparingInt(SourcePartition::partition));
        sorted.putAll(offsets.getOrDefault(new Game(scopeId, gameId), Map.of()));
        return sorted;
    }

    @Override
    public synchronized void purge(UUID scopeId) {
        headers.remove(scopeId);
        steps.keySet().removeIf(game -> game.scopeId().equals(scopeId));
        events.keySet().removeIf(game -> game.scopeId().equals(scopeId));
        offsets.keySet().removeIf(game -> game.scopeId().equals(scopeId));
    }

    /** Alters the retained transcript of the scope in place, as tampering with storage would. */
    public synchronized void tamperWithTranscript(UUID scopeId) {
        artifacts.put(headers.get(scopeId).transcriptArtifact(), "tampered".getBytes(StandardCharsets.UTF_8));
    }

    /** Removes the retained manifest of the scope, as a lost artifact would. */
    public synchronized void loseManifest(UUID scopeId) {
        artifacts.remove(headers.get(scopeId).manifestArtifact());
    }

    private String put(String text) {
        var content = text.getBytes(StandardCharsets.UTF_8);
        var digest = sha256(content);
        artifacts.putIfAbsent(digest, content);
        return digest;
    }

    private String text(String digest) {
        var content = artifacts.get(digest);
        if (content == null) {
            throw new ManifestMismatchException("Pinned artifact " + digest + " is unavailable");
        }
        if (!sha256(content).equals(digest)) {
            throw new ManifestMismatchException("Pinned artifact " + digest + " does not match its content address");
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
