package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionState;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/** Reproductions, claimed under a lease so a stalled worker can never settle a re-run. */
@Component
public class ReproductionRepositoryAdapter implements ReproductionRepository {

    private static final String QUEUED = "QUEUED";
    private static final String RUNNING = "RUNNING";

    private final ReproductionJpaRepository reproductions;
    private final ClaimQueries claims;
    private final InsertOnce insertOnce;
    private final DivergenceJson divergences;

    ReproductionRepositoryAdapter(
            ReproductionJpaRepository reproductions,
            ClaimQueries claims,
            InsertOnce insertOnce,
            DivergenceJson divergences) {
        this.reproductions = reproductions;
        this.claims = claims;
        this.insertOnce = insertOnce;
        this.divergences = divergences;
    }

    @Override
    public ReproductionCreation create(UUID idempotencyKey, String requestHash, Reproduction reproduction) {
        return findByKey(idempotencyKey)
                .<ReproductionCreation>map(ReproductionCreation.Existing::new)
                .orElseGet(() -> insertOrExisting(idempotencyKey, requestHash, reproduction));
    }

    private ReproductionCreation insertOrExisting(UUID idempotencyKey, String requestHash, Reproduction reproduction) {
        var stored = insertOnce.insert(
                () -> reproductions.saveAndFlush(new ReproductionJpaEntity(reproduction, idempotencyKey, requestHash)));
        return stored
                ? new ReproductionCreation.Created()
                : new ReproductionCreation.Existing(findByKey(idempotencyKey).orElseThrow());
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
    public Optional<Reproduction> claimNext(String owner, Instant leaseUntil, Map<String, LaneFit> freeLanes) {
        return claims.lockOldestReproductionServedBy(QUEUED, freeLanes)
                .flatMap(reproductions::findById)
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
                            settled.firstDivergence() == null ? null : divergences.write(settled.firstDivergence()),
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
                entity.divergenceJson() == null ? null : divergences.read(entity.divergenceJson()),
                Mappings.failure(entity.failureCode(), entity.failureMessage()),
                entity.createdAt(),
                entity.finishedAt());
    }
}
