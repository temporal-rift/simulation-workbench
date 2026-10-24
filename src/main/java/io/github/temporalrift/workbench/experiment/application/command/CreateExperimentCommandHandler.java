package io.github.temporalrift.workbench.experiment.application.command;

import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentValidator;
import io.github.temporalrift.workbench.experiment.domain.IdempotencyConflictException;
import io.github.temporalrift.workbench.experiment.domain.ManifestDigest;
import io.github.temporalrift.workbench.experiment.domain.StoredManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * Validates, digests, and freezes an experiment exactly once per idempotency key. A repeated key
 * with an identical canonical body returns the original experiment; a changed body conflicts.
 */
public class CreateExperimentCommandHandler implements CreateExperimentUseCase {

    private final ExperimentRepository experiments;
    private final IdempotencyStore idempotency;
    private final Clock clock;
    private final PolicyReferenceVerifier policyVerifier;
    private final VariantRules variantRules;

    public CreateExperimentCommandHandler(
            ExperimentRepository experiments,
            IdempotencyStore idempotency,
            Clock clock,
            PolicyReferenceVerifier policyVerifier,
            VariantRules variantRules) {
        this.experiments = experiments;
        this.idempotency = idempotency;
        this.clock = clock;
        this.policyVerifier = policyVerifier;
        this.variantRules = variantRules;
    }

    @Override
    public Result handle(Command command) {
        if (command.idempotencyKey() == null) {
            throw new IllegalArgumentException("Idempotency-Key is required");
        }
        var view = ExperimentValidator.validate(command.manifest(), policyVerifier);
        var frozen = variantRules.freeze(command.manifest());
        var digest = ManifestDigest.sha256Hex(frozen.manifest());
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
        var manifestJson = frozen.manifest().toString();
        // The claim references the experiment row, so the experiment is saved first and the key
        // is claimed with an insert-if-absent: a racing request loses the insert and recovers by
        // re-reading the winning claim. Each repository call runs in its own transaction (the
        // controller is not transactional).
        experiments.save(experimentId, digest, manifestJson, view.name(), createdAt, frozen.baselineDocuments());
        if (idempotency.saveIfAbsent(command.idempotencyKey(), requestHash, experimentId, createdAt)) {
            return stored(experimentId);
        }
        return recoverFromRace(command.idempotencyKey(), requestHash, experimentId);
    }

    private Result recoverFromRace(UUID key, String requestHash, UUID loserId) {
        var raced = idempotency
                .findByKey(key)
                .orElseThrow(() -> new IllegalStateException("Idempotency claim vanished for key " + key));
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
        return new Result(
                stored.experimentId(),
                StoredManifests.tree(stored),
                stored.manifestDigest(),
                DateTimeFormatter.ISO_INSTANT.format(stored.createdAt()));
    }

    private static String requestHash(UUID key, String digest) {
        return Sha256.hexUtf8(key + "|" + digest);
    }
}
