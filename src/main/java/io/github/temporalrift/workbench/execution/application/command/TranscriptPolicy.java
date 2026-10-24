package io.github.temporalrift.workbench.execution.application.command;

import java.util.Optional;
import java.util.Set;

import io.github.temporalrift.workbench.execution.domain.evidence.DecisionTranscript;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * Plays a saved case instead of deciding: in each window it chooses exactly the decision the transcript
 * accepted for the seat. A window the transcript does not cover, or a decision the service now refuses, is
 * not papered over; the policy is exhausted and the reproduction reports where it stopped.
 */
final class TranscriptPolicy implements BotPolicy {

    private final DecisionTranscript transcript;
    private Miss miss;

    TranscriptPolicy(DecisionTranscript transcript) {
        this.transcript = transcript;
    }

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var window = observation.window();
        if (window instanceof DecisionWindow.TerminalReadiness) {
            return new PolicyDecision.Chosen(new Candidate.ConfirmReady());
        }
        var decision = transcript.decisionFor(observation.seatIndex(), window.ref());
        if (decision.isEmpty()) {
            miss = new Miss(observation.seatIndex(), window.ref(), null);
            return new PolicyDecision.Exhausted();
        }
        if (excluded.contains(decision.get())) {
            miss = new Miss(observation.seatIndex(), window.ref(), decision.get());
            return new PolicyDecision.Exhausted();
        }
        return new PolicyDecision.Chosen(decision.get());
    }

    /** Where the transcript could not be followed, if it could not. */
    Optional<Miss> miss() {
        return Optional.ofNullable(miss);
    }

    /**
     * @param refused the transcript decision the service refused, or null when the window has no decision
     */
    record Miss(int seatIndex, WindowRef window, Candidate refused) {}
}
