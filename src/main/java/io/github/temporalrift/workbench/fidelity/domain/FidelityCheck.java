package io.github.temporalrift.workbench.fidelity.domain;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * The record of one check. It is queued when accepted, runs while its experiment is played on the simulator and its
 * cases are reproduced on the real lane, and ends {@code PASSED} or {@code FAILED}. Transitions return a new value.
 *
 * <pre>
 * QUEUED ──begin──▶ RUNNING ──every case MATCH──▶ PASSED
 *    │                 └──a case differs, or cannot complete──▶ FAILED
 *    └──cannot begin──▶ FAILED
 * </pre>
 *
 * @param lane the environment of the real lane and {@code laneId} its id, chosen when the check is accepted
 * @param experimentId the frozen experiment and {@code runId} its run; null until the check has begun
 * @param failure set only when the check failed for a reason other than its cases' results
 */
public record FidelityCheck(
        UUID id,
        String seed,
        FidelityCheckState state,
        FidelityEnvironment simulator,
        FidelityEnvironment lane,
        String laneId,
        List<FidelityVariant> variants,
        UUID experimentId,
        UUID runId,
        FidelityFailure failure,
        Instant createdAt,
        Instant startedAt,
        Instant finishedAt) {

    public FidelityCheck {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(seed, "seed");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(simulator, "simulator");
        Objects.requireNonNull(lane, "lane");
        Objects.requireNonNull(laneId, "laneId");
        Objects.requireNonNull(createdAt, "createdAt");
        variants = List.copyOf(variants);
    }

    public static FidelityCheck queued(
            UUID id,
            String seed,
            FidelityEnvironment simulator,
            FidelityEnvironment lane,
            String laneId,
            List<FidelityVariant> variants,
            Instant now) {
        return new FidelityCheck(
                id,
                seed,
                FidelityCheckState.QUEUED,
                simulator,
                lane,
                laneId,
                variants,
                null,
                null,
                null,
                now,
                null,
                null);
    }

    /** The check has frozen its experiment and started its run. */
    public FidelityCheck begun(UUID experiment, UUID run, Instant now) {
        requireState(FidelityCheckState.QUEUED);
        return new FidelityCheck(
                id,
                seed,
                FidelityCheckState.RUNNING,
                simulator,
                lane,
                laneId,
                variants,
                experiment,
                run,
                null,
                createdAt,
                now,
                null);
    }

    /** Every case matched. */
    public FidelityCheck passed(Instant now) {
        requireState(FidelityCheckState.RUNNING);
        return settled(FidelityCheckState.PASSED, null, now);
    }

    /** A case differed or could not be reproduced. */
    public FidelityCheck failedByCases(Instant now) {
        requireState(FidelityCheckState.RUNNING);
        return settled(FidelityCheckState.FAILED, null, now);
    }

    /** The check could not complete, for a reason other than its cases. */
    public FidelityCheck failed(FidelityFailure reason, Instant now) {
        if (!state.isOpen()) {
            throw new IllegalStateException("A " + state + " check cannot fail again");
        }
        return settled(FidelityCheckState.FAILED, Objects.requireNonNull(reason, "reason"), now);
    }

    /** {@code PASSED} or {@code FAILED} once the check has ended, otherwise null. */
    public FidelityVerdict verdict() {
        return switch (state) {
            case PASSED -> FidelityVerdict.PASSED;
            case FAILED -> FidelityVerdict.FAILED;
            case QUEUED, RUNNING -> null;
        };
    }

    private FidelityCheck settled(FidelityCheckState next, FidelityFailure reason, Instant now) {
        return new FidelityCheck(
                id,
                seed,
                next,
                simulator,
                lane,
                laneId,
                variants,
                experimentId,
                runId,
                reason,
                createdAt,
                startedAt,
                now);
    }

    private void requireState(FidelityCheckState expected) {
        if (state != expected) {
            throw new IllegalStateException("A " + state + " check is not " + expected);
        }
    }
}
