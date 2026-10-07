package io.github.temporalrift.workbench.experiment.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;

/**
 * Validates, digests, and freezes an experiment exactly once per idempotency key. A repeated key
 * with an identical canonical body returns the original experiment; a changed body conflicts.
 */
public class CreateExperimentCommandHandler implements CreateExperimentUseCase {

    private final ExperimentRepository experiments;
    private final IdempotencyStore idempotency;
    private final Clock clock;

    public CreateExperimentCommandHandler(ExperimentRepository experiments, IdempotencyStore idempotency, Clock clock) {
        this.experiments = experiments;
        this.idempotency = idempotency;
        this.clock = clock;
    }

    @Override
    public Result handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var view = ExperimentValidator.validate(command.manifest());
        var digest = ManifestDigest.sha256Hex(command.manifest());
        var requestHash = requestHash(command.idempotencyKey(), digest);
        var existing = idempotency.findByKey(command.idempotencyKey());
        if (existing.isPresent()) {
            if (!existing.get().requestHash().equals(requestHash)) {
                throw new IdempotencyConflictException("Idempotency-Key was reused with a different body");
            }
            return stored(existing.get().experimentId());
        }
        var experimentId = UUID.randomUUID();
        var createdAt = Instant.now(clock);
        var manifestJson = command.manifest().toString();
        // The claim references the experiment row, so the experiment is saved first and the claim
        // is an insert-only flushed write: a racing request surfaces here, inside this call, while
        // each repository call runs in its own transaction (the controller is not transactional).
        experiments.save(experimentId, digest, manifestJson, view.name(), createdAt);
        try {
            idempotency.claim(command.idempotencyKey(), requestHash, experimentId, createdAt);
        } catch (DataIntegrityViolationException duplicate) {
            return recoverFromRace(command.idempotencyKey(), requestHash, experimentId, duplicate);
        }
        return stored(experimentId);
    }

    private Result recoverFromRace(UUID key, String requestHash, UUID loserId, RuntimeException duplicate) {
        var raced = idempotency.findByKey(key).orElseThrow(() -> duplicate);
        if (!raced.requestHash().equals(requestHash)) {
            throw new IdempotencyConflictException("Idempotency-Key was reused with a different body");
        }
        // Our experiment row is unreachable by anyone; remove it so a lost race leaves no orphans.
        experiments.delete(loserId);
        return stored(raced.experimentId());
    }

    private Result stored(UUID experimentId) {
        var stored = experiments
                .findById(experimentId)
                .orElseThrow(() -> new IllegalStateException("Experiment vanished after freeze: " + experimentId));
        try {
            var mapper = new tools.jackson.databind.ObjectMapper();
            JsonNode manifest = mapper.readTree(stored.manifestJson());
            return new Result(
                    stored.experimentId(),
                    manifest,
                    stored.manifestDigest(),
                    DateTimeFormatter.ISO_INSTANT.format(stored.createdAt()));
        } catch (tools.jackson.core.JacksonException e) {
            throw new IllegalStateException("Stored manifest is not valid JSON", e);
        }
    }

    private static String requestHash(UUID key, String digest) {
        try {
            var bytes = (key + "|" + digest).getBytes(StandardCharsets.UTF_8);
            var hashed = MessageDigest.getInstance("SHA-256").digest(bytes);
            var hex = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
