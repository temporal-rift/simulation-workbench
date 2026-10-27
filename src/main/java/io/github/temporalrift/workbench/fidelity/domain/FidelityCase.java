package io.github.temporalrift.workbench.fidelity.domain;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * One case of a check: its matrix coordinate, the simulator case it was played as and its reproduction on the real
 * lane. {@code factionSet} is ordered by faction name, as the matrix orders it.
 *
 * @param ordinal the case's position in matrix order
 * @param runCaseId the case of the simulator run; {@code reproductionId} its reproduction, null until requested
 * @param firstDivergence set only when {@code DIVERGED}; {@code failure} only when {@code UNREPRODUCIBLE}
 */
public record FidelityCase(
        int ordinal,
        UUID caseKey,
        VariantLabel variant,
        int playerCount,
        List<Faction> factionSet,
        int seatRotation,
        String policyId,
        UUID runCaseId,
        UUID reproductionId,
        FidelityCaseResult result,
        FirstDivergence firstDivergence,
        FidelityFailure failure) {

    public FidelityCase {
        Objects.requireNonNull(caseKey, "caseKey");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(runCaseId, "runCaseId");
        Objects.requireNonNull(result, "result");
        factionSet = List.copyOf(factionSet);
        if (firstDivergence != null && result != FidelityCaseResult.DIVERGED) {
            throw new IllegalArgumentException("only a DIVERGED case has a first divergence");
        }
        if (failure != null && result != FidelityCaseResult.UNREPRODUCIBLE) {
            throw new IllegalArgumentException("only an UNREPRODUCIBLE case has a failure");
        }
    }

    public static FidelityCase pending(
            int ordinal,
            UUID caseKey,
            VariantLabel variant,
            int playerCount,
            List<Faction> factionSet,
            int seatRotation,
            String policyId,
            UUID runCaseId) {
        return new FidelityCase(
                ordinal,
                caseKey,
                variant,
                playerCount,
                factionSet,
                seatRotation,
                policyId,
                runCaseId,
                null,
                FidelityCaseResult.PENDING,
                null,
                null);
    }

    /** The same case at another position of the matrix. */
    public FidelityCase withOrdinal(int position) {
        return new FidelityCase(
                position,
                caseKey,
                variant,
                playerCount,
                factionSet,
                seatRotation,
                policyId,
                runCaseId,
                reproductionId,
                result,
                firstDivergence,
                failure);
    }

    /** The case could not be played on the simulator or reproduced, so it says nothing about fidelity. */
    public FidelityCase unreproducible(FidelityFailure reason) {
        return with(FidelityCaseResult.UNREPRODUCIBLE, null, reason, reproductionId);
    }

    /** The reproduction was requested. */
    public FidelityCase reproducing(UUID reproduction) {
        return with(FidelityCaseResult.PENDING, null, null, reproduction);
    }

    public FidelityCase matched() {
        return with(FidelityCaseResult.MATCH, null, null, reproductionId);
    }

    public FidelityCase diverged(FirstDivergence divergence) {
        return with(FidelityCaseResult.DIVERGED, Objects.requireNonNull(divergence), null, reproductionId);
    }

    private FidelityCase with(
            FidelityCaseResult next, FirstDivergence divergence, FidelityFailure reason, UUID reproduction) {
        return new FidelityCase(
                ordinal,
                caseKey,
                variant,
                playerCount,
                factionSet,
                seatRotation,
                policyId,
                runCaseId,
                reproduction,
                next,
                divergence,
                reason);
    }
}
