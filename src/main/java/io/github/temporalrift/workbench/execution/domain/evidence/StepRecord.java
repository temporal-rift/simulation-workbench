package io.github.temporalrift.workbench.execution.domain.evidence;

import java.time.Instant;
import java.util.Objects;

import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * One command a seat sent, with the entitled observation it decided from and what the service answered.
 * The observation holds only what the seat was entitled to see, so a seat's steps can be shown to that seat
 * without filtering.
 *
 * @param step position in the game's evidence stream, assigned when the step is appended
 * @param window the decision window the command was sent in
 * @param outcomeCode the service's refusal code for a rejected command
 * @param entropy the policy entropy coordinates the decision drew from, or null when it drew none
 */
public record StepRecord(
        int step,
        int seatIndex,
        WindowRef window,
        Instant logicalTime,
        EntitledObservation observation,
        Candidate decision,
        StepOutcome outcome,
        String outcomeCode,
        StepEntropy entropy) {

    public StepRecord {
        Objects.requireNonNull(window, "window");
        Objects.requireNonNull(logicalTime, "logicalTime");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(outcome, "outcome");
    }

    public StepRecord numbered(int assigned) {
        return new StepRecord(
                assigned, seatIndex, window, logicalTime, observation, decision, outcome, outcomeCode, entropy);
    }

    public StepRecord withOutcome(StepOutcome resolved, String code) {
        return new StepRecord(step, seatIndex, window, logicalTime, observation, decision, resolved, code, entropy);
    }

    public boolean isAccepted() {
        return outcome == StepOutcome.ACCEPTED;
    }
}
