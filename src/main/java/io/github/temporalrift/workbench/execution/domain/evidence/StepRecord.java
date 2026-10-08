package io.github.temporalrift.workbench.execution.domain.evidence;

import java.time.Instant;
import java.util.Objects;

/**
 * One command a seat sent, with the entitled observation it decided from and what the service answered.
 * Observation and entropy are retained as canonical JSON text; the observation holds only what the seat
 * was entitled to see, so a seat's steps can be shown to that seat without filtering.
 *
 * @param step position in the game's evidence stream, assigned when the step is appended
 * @param phase the kind of decision window, for example {@code ACTION_ROUND}
 * @param era the window's era, or null when it has none
 * @param round the window's action round, or null outside action rounds
 * @param decision canonical candidate text
 * @param outcomeCode the service's refusal code for a rejected command
 * @param entropy the policy entropy coordinates the decision drew from
 */
public record StepRecord(
        int step,
        int seatIndex,
        String windowKey,
        String phase,
        Integer era,
        Integer round,
        Instant logicalTime,
        String observation,
        String decision,
        StepOutcome outcome,
        String outcomeCode,
        String entropy) {

    public StepRecord {
        Objects.requireNonNull(windowKey, "windowKey");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(logicalTime, "logicalTime");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(outcome, "outcome");
    }

    public StepRecord numbered(int assigned) {
        return new StepRecord(
                assigned,
                seatIndex,
                windowKey,
                phase,
                era,
                round,
                logicalTime,
                observation,
                decision,
                outcome,
                outcomeCode,
                entropy);
    }

    public StepRecord withOutcome(StepOutcome resolved, String code) {
        return new StepRecord(
                step,
                seatIndex,
                windowKey,
                phase,
                era,
                round,
                logicalTime,
                observation,
                decision,
                resolved,
                code,
                entropy);
    }

    public boolean isAccepted() {
        return outcome == StepOutcome.ACCEPTED;
    }
}
