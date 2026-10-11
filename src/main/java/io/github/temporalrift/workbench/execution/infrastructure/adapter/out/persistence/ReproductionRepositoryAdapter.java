package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;

import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionState;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/** Reproductions, claimed under a lease so a stalled worker can never settle a re-run. */
@Component
public class ReproductionRepositoryAdapter implements ReproductionRepository {

    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";
    private static final TypeReference<Map<String, Object>> OBJECT = new TypeReference<>() {};

    private final ReproductionJpaRepository reproductions;
    private final InsertOnce insertOnce;
    private final StoredJson json;

    ReproductionRepositoryAdapter(ReproductionJpaRepository reproductions, InsertOnce insertOnce, StoredJson json) {
        this.reproductions = reproductions;
        this.insertOnce = insertOnce;
        this.json = json;
    }

    @Override
    public ReproductionCreation create(UUID idempotencyKey, String requestHash, Reproduction reproduction) {
        if (reproductions.findByIdempotencyKey(idempotencyKey).isEmpty()) {
            var stored = insertOnce.insert(() ->
                    reproductions.saveAndFlush(new ReproductionJpaEntity(reproduction, idempotencyKey, requestHash)));
            if (stored) {
                return new ReproductionCreation.Created();
            }
        }
        return new ReproductionCreation.Existing(findByKey(idempotencyKey).orElseThrow());
    }

    @Override
    public Optional<ReproductionClaim> findByKey(UUID idempotencyKey) {
        return reproductions
                .findByIdempotencyKey(idempotencyKey)
                .map(entity -> new ReproductionClaim(reproduction(entity), entity.requestHash()));
    }

    @Override
    public Optional<Reproduction> find(UUID reproductionId) {
        return reproductions.findById(reproductionId).map(this::reproduction);
    }

    @Override
    @Transactional
    public Optional<Reproduction> claimNext(
            String owner, Instant now, Instant leaseUntil, Collection<String> freeLaneIds) {
        if (freeLaneIds.isEmpty()) {
            return Optional.empty();
        }
        return reproductions
                .findFirstWithLockByStateAndLaneIdInOrderByCreatedAt(QUEUED, freeLaneIds)
                .map(entity -> {
                    entity.claim(owner, leaseUntil);
                    return reproduction(entity);
                });
    }

    @Override
    @Transactional
    public boolean extendLease(UUID reproductionId, String owner, Instant leaseUntil) {
        return reproductions
                .findWithLockByReproductionIdAndStateAndLeaseOwner(reproductionId, RUNNING, owner)
                .map(entity -> {
                    entity.extendLease(leaseUntil);
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public boolean settle(UUID reproductionId, String owner, Reproduction settled) {
        var failure = settled.failure();
        return reproductions
                .findWithLockByReproductionIdAndStateAndLeaseOwner(reproductionId, RUNNING, owner)
                .map(entity -> {
                    entity.settle(
                            settled.state().name(),
                            settled.firstDivergence() == null ? null : divergenceJson(settled.firstDivergence()),
                            failure == null ? null : failure.code().name(),
                            failure == null ? null : failure.message(),
                            settled.finishedAt());
                    return true;
                })
                .orElse(false);
    }

    @Override
    @Transactional
    public void release(UUID reproductionId, String owner) {
        reproductions
                .findWithLockByReproductionIdAndStateAndLeaseOwner(reproductionId, RUNNING, owner)
                .ifPresent(ReproductionJpaEntity::requeue);
    }

    @Override
    @Transactional
    public int requeueExpired(Instant now) {
        return requeue(reproductions.findAllWithLockByStateAndLeaseExpiresAtBefore(RUNNING, now));
    }

    @Override
    @Transactional
    public int requeueOwnedBy(String owner) {
        return requeue(reproductions.findAllWithLockByStateAndLeaseOwner(RUNNING, owner));
    }

    @Override
    @Transactional
    public int requeueAllRunning() {
        return requeue(reproductions.findAllWithLockByState(RUNNING));
    }

    private static int requeue(List<ReproductionJpaEntity> running) {
        running.forEach(ReproductionJpaEntity::requeue);
        return running.size();
    }

    private Reproduction reproduction(ReproductionJpaEntity entity) {
        return new Reproduction(
                entity.reproductionId(),
                entity.attemptId(),
                entity.runId(),
                entity.caseId(),
                entity.laneId(),
                ReproductionState.valueOf(entity.state()),
                entity.divergenceJson() == null ? null : divergence(entity.divergenceJson()),
                Mappings.failure(entity.failureCode(), entity.failureMessage()),
                entity.createdAt(),
                entity.finishedAt());
    }

    private String divergenceJson(Divergence divergence) {
        return json.write(Map.of(
                "step", divergence.step(),
                "kind", divergence.kind(),
                "expected", divergence.expected(),
                "actual", divergence.actual()));
    }

    @SuppressWarnings("unchecked")
    private Divergence divergence(String stored) {
        var values = json.read(stored, OBJECT);
        return new Divergence(
                ((Number) values.get("step")).intValue(),
                (String) values.get("kind"),
                (Map<String, Object>) values.get("expected"),
                (Map<String, Object>) values.get("actual"));
    }
}
