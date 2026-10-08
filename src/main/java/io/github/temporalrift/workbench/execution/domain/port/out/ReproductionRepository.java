package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionClaim;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;

/** Durable reproductions with the same lease fencing the research cases have. */
public interface ReproductionRepository {

    /**
     * Stores the queued reproduction and claims the idempotency key in one step. When the key is already
     * claimed nothing is stored and the existing claim is returned.
     */
    ReproductionCreation create(UUID idempotencyKey, String requestHash, Reproduction reproduction);

    /** The claim an idempotency key already holds, if any. */
    Optional<ReproductionClaim> findByKey(UUID idempotencyKey);

    Optional<Reproduction> find(UUID reproductionId);

    /**
     * Atomically moves the oldest queued reproduction whose lane is among {@code freeLaneIds} to running under
     * a lease.
     */
    Optional<Reproduction> claimNext(String owner, Instant now, Instant leaseUntil, Collection<String> freeLaneIds);

    /** Extends the lease; false means the reproduction is no longer this owner's to run. */
    boolean extendLease(UUID reproductionId, String owner, Instant leaseUntil);

    /** Records the final state; false when the lease was lost. */
    boolean settle(UUID reproductionId, String owner, Reproduction settled);

    /** Gives a claim back unrun when no lane could be acquired. */
    void release(UUID reproductionId, String owner);

    /** Queues running reproductions whose lease expired. */
    int requeueExpired(Instant now);

    /** Queues the reproductions this owner holds, as on a graceful shutdown. */
    int requeueOwnedBy(String owner);

    /** Queues every running reproduction a previous process left behind. */
    int requeueAllRunning();
}
