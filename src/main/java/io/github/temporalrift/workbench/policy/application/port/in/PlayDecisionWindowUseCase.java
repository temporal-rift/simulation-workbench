package io.github.temporalrift.workbench.policy.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;

/** Plays one decision window for every seat of a case. */
public interface PlayDecisionWindowUseCase {

    /**
     * Freezes every seat's observation, computes every seat's choice, then submits in seat order.
     *
     * @param maxRejectedCandidatesPerWindow the manifest's per-seat rejection budget
     */
    List<SeatResult> play(List<SeatPolicy> seats, int maxRejectedCandidatesPerWindow);

    /** A seat's policy and its policy seed. */
    record SeatPolicy(int seatIndex, BotPolicy policy, long policySeed) {}

    record SeatResult(int seatIndex, DecisionResult result) {}

    sealed interface DecisionResult {

        /**
         * The service holds this submission for the seat; {@code recovered} marks one confirmed by
         * reconciliation after a lost response.
         */
        record Submitted(Candidate candidate, List<String> rejectionCodes, boolean recovered)
                implements DecisionResult {
            public Submitted {
                rejectionCodes = List.copyOf(rejectionCodes);
            }
        }

        /** {@code POLICY_EXHAUSTED}: no candidate, pass or decline remained. */
        record PolicyExhausted(List<String> rejectionCodes) implements DecisionResult {
            public PolicyExhausted {
                rejectionCodes = List.copyOf(rejectionCodes);
            }
        }
    }
}
