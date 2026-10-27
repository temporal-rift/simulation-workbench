package io.github.temporalrift.workbench.fidelity.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.domain.CheckClaim;
import io.github.temporalrift.workbench.fidelity.domain.CheckCreation;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCaseResult;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckState;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCounts;
import io.github.temporalrift.workbench.fidelity.domain.SourceRevisions;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.shared.Page;

/** Durable fidelity records: checks and their cases. */
public interface FidelityRepository {

    /**
     * Stores the queued check and claims the idempotency key in one step. When the key is already claimed, or another
     * check is open, nothing is stored.
     */
    CheckCreation create(UUID idempotencyKey, String requestHash, FidelityCheck queued);

    Optional<CheckClaim> findClaim(UUID idempotencyKey);

    Optional<FidelityCheck> find(UUID id);

    /** The check that is queued or running; there is at most one. */
    Optional<FidelityCheck> open();

    /** Checks newest first, filtered by state when given. */
    Page<FidelityCheck> list(FidelityCheckState state, int limit, int offset);

    /** Stores the check's next state; false when it was no longer in {@code expected}. */
    boolean update(FidelityCheck next, FidelityCheckState expected);

    /** The newest {@code PASSED} check whose simulator ran exactly these source revisions. */
    Optional<FidelityCheck> newestPassed(SourceRevisions revisions);

    /** Stores the cases of a check that has none yet; cases already stored are left as they are. */
    void insertCases(UUID checkId, List<FidelityCase> cases);

    int caseCount(UUID checkId);

    FidelityCounts counts(UUID checkId);

    /** Pending cases whose reproduction has not been requested, in matrix order, at most {@code limit}. */
    List<FidelityCase> casesToReproduce(UUID checkId, int limit);

    /** Pending cases whose reproduction is requested. */
    List<FidelityCase> casesAwaitingReproduction(UUID checkId);

    void updateCase(UUID checkId, FidelityCase updated);

    /** Cases in matrix order, filtered by variant and result when given. */
    Page<FidelityCase> cases(UUID checkId, VariantLabel variant, FidelityCaseResult result, int limit, int offset);
}
