package io.github.temporalrift.workbench.policy.application.command;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.ReconciliationPendingException;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/**
 * Plays a decision window: observations are frozen and every choice computed before any
 * submission; rejections exclude the rejected candidate and reselect from a refreshed observation
 * within the budget; a lost response is reconciled against accepted state before any retry.
 */
public class DecisionWindowService implements PlayDecisionWindowUseCase {

    private static final int MAX_PENDING_POLLS = 3;
    private static final int MAX_SUBMISSIONS_PER_CANDIDATE = 2;

    private final ParticipantGateway gateway;

    public DecisionWindowService(ParticipantGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public List<SeatResult> play(List<SeatPolicy> seats, int maxRejectedCandidatesPerWindow) {
        var ordered = seats.stream()
                .sorted(Comparator.comparingInt(SeatPolicy::seatIndex))
                .toList();
        var frozen =
                ordered.stream().map(seat -> gateway.observe(seat.seatIndex())).toList();
        var firstChoices = new ArrayList<PolicyDecision>();
        for (var i = 0; i < ordered.size(); i++) {
            firstChoices.add(decide(ordered.get(i), frozen.get(i), Set.of(), 0));
        }
        var results = new ArrayList<SeatResult>();
        for (var i = 0; i < ordered.size(); i++) {
            var seat = ordered.get(i);
            results.add(new SeatResult(
                    seat.seatIndex(),
                    submit(seat, frozen.get(i), firstChoices.get(i), maxRejectedCandidatesPerWindow)));
        }
        return results;
    }

    private DecisionResult submit(
            SeatPolicy seat, EntitledObservation frozen, PolicyDecision first, int maxRejectedCandidates) {
        var excluded = new LinkedHashSet<Candidate>();
        var codes = new ArrayList<String>();
        var observation = frozen;
        var decision = first;
        while (true) {
            if (!(decision instanceof PolicyDecision.Chosen(var candidate))) {
                return new DecisionResult.PolicyExhausted(codes);
            }
            var confirmed = submitConfirmed(seat.seatIndex(), candidate);
            if (confirmed.outcome() instanceof SubmissionOutcome.Rejected(var code)) {
                excluded.add(candidate);
                codes.add(code);
                observation = gateway.observe(seat.seatIndex());
                decision = codes.size() >= maxRejectedCandidates
                        ? fallback(observation, excluded)
                        : decide(seat, observation, excluded, codes.size());
            } else {
                return new DecisionResult.Submitted(confirmed.accepted(), codes, confirmed.recovered());
            }
        }
    }

    /** Submits, reconciling a missing acknowledgement before it will submit the same candidate again. */
    private Confirmed submitConfirmed(int seatIndex, Candidate candidate) {
        for (var attempt = 0; attempt < MAX_SUBMISSIONS_PER_CANDIDATE; attempt++) {
            var outcome = gateway.submit(seatIndex, candidate);
            if (outcome instanceof SubmissionOutcome.Accepted) {
                return new Confirmed(outcome, candidate, false);
            }
            if (outcome instanceof SubmissionOutcome.Rejected) {
                return new Confirmed(outcome, null, false);
            }
            if (reconcile(seatIndex) instanceof Reconciliation.Accepted(var accepted)) {
                return new Confirmed(new SubmissionOutcome.Accepted(), accepted, true);
            }
        }
        throw new ReconciliationPendingException(
                "seat " + seatIndex + " submission was neither acknowledged nor found in accepted state");
    }

    private Reconciliation reconcile(int seatIndex) {
        for (var poll = 0; poll < MAX_PENDING_POLLS; poll++) {
            var state = gateway.reconcile(seatIndex);
            if (!(state instanceof Reconciliation.Pending)) {
                return state;
            }
        }
        throw new ReconciliationPendingException("seat " + seatIndex + " accepted state is not yet current");
    }

    private static PolicyDecision decide(
            SeatPolicy seat, EntitledObservation observation, Set<Candidate> excluded, int rejections) {
        var entropy = PolicyEntropy.derive(
                seat.policySeed(), seat.seatIndex(), observation.window().key(), rejections);
        return seat.policy().decide(observation, excluded, entropy);
    }

    private static PolicyDecision fallback(EntitledObservation observation, Set<Candidate> excluded) {
        return CandidateGenerator.generate(observation).stream()
                .filter(Candidate::isFallback)
                .filter(candidate -> !excluded.contains(candidate))
                .findFirst()
                .<PolicyDecision>map(PolicyDecision.Chosen::new)
                .orElseGet(PolicyDecision.Exhausted::new);
    }

    private record Confirmed(SubmissionOutcome outcome, Candidate accepted, boolean recovered) {}
}
