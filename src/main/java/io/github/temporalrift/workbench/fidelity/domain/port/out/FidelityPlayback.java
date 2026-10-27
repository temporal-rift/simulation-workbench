package io.github.temporalrift.workbench.fidelity.domain.port.out;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.shared.game.Faction;

/** Plays the experiment on the simulator and reproduces its cases on the real lane. */
public interface FidelityPlayback {

    /**
     * Creates the run of the frozen experiment, once per idempotency key.
     *
     * Refused with a fidelity refusal exception when no lane serves some variant
     */
    UUID startRun(UUID experimentId, UUID idempotencyKey);

    Optional<RunStatus> run(UUID runId);

    /**
     * Resumes an interrupted run, which makes no progress until it is.
     *
     * @throws FidelityPlaybackRefusedException when the run is not interrupted
     */
    void resumeRun(UUID runId);

    /** Every case of the run in matrix order. */
    List<PlayedCase> cases(UUID runId);

    /**
     * Queues the reproduction of a succeeded case on the lane, once per idempotency key.
     *
     * Refused with a fidelity refusal exception when the lane no longer plays the game of the case's lane
     */
    UUID reproduce(UUID runId, UUID runCaseId, String laneId, UUID idempotencyKey);

    Optional<Reproduced> reproduction(UUID reproductionId);

    /** Where the run stands; {@code failureMessage} is set only when it failed. */
    record RunStatus(Phase phase, String failureMessage) {

        /** ACTIVE and INTERRUPTED can still end; an interrupted run needs to be resumed. */
        public enum Phase {
            ACTIVE,
            INTERRUPTED,
            COMPLETED,
            FAILED,
            CANCELLED
        }
    }

    /**
     * A case of the run, with the coordinate the matrix gave it.
     *
     * @param succeeded whether the simulator played the case to an end
     * @param failure why it did not, when it did not
     */
    record PlayedCase(
            UUID runCaseId,
            UUID caseKey,
            String variantLabel,
            int playerCount,
            List<Faction> factionSet,
            int seatRotation,
            String policyId,
            boolean succeeded,
            FidelityFailure failure) {

        public PlayedCase {
            factionSet = List.copyOf(factionSet);
        }
    }

    /** Where a reproduction stands. */
    record Reproduced(Status status, FirstDivergence firstDivergence, FidelityFailure failure) {

        /** Whether the reproduction will not change again. */
        public boolean finished() {
            return status != Status.QUEUED && status != Status.RUNNING;
        }

        /** The reproduction's state. */
        public enum Status {
            QUEUED,
            RUNNING,
            MATCH,
            DIVERGED,
            FAILED
        }
    }
}
