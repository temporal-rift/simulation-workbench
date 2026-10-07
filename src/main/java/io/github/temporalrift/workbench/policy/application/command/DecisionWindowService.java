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
                    seat.seatIndex(), submit(seat, firstChoices.get(i), maxRejectedCandidatesPerWindow)));
        }
        return results;
    }

    private DecisionResult submit(SeatPolicy seat, PolicyDecision first, int maxRejectedCandidates) {
        var excluded = new LinkedHashSet<Candidate>();
        var codes = new ArrayList<String>();
        var decision = first;
        while (decision instanceof PolicyDecision.Chosen(var candidate)) {
            switch (submitConfirmed(seat.seatIndex(), candidate)) {
                case Confirmation.Accepted(var accepted, var recovered) -> {
                    return new DecisionResult.Submitted(accepted, codes, recovered);
                }
                case Confirmation.NotAccepted() -> {
                    return new DecisionResult.NotAccepted(candidate, codes);
                }
                case Confirmation.Pending() -> {
                    return new DecisionResult.ReconciliationPending(candidate, codes);
                }
                case Confirmation.Rejected(var code) -> {
                    excluded.add(candidate);
                    codes.add(code);
                    var refreshed = gateway.observe(seat.seatIndex());
                    decision = codes.size() >= maxRejectedCandidates
                            ? fallback(refreshed, excluded)
                            : decide(seat, refreshed, excluded, codes.size());
                }
            }
        }
        return new DecisionResult.PolicyExhausted(codes);
    }

    /** Submits, reconciling a missing acknowledgement before it will submit the same candidate again. */
    private Confirmation submitConfirmed(int seatIndex, Candidate candidate) {
        for (var attempt = 0; attempt < MAX_SUBMISSIONS_PER_CANDIDATE; attempt++) {
            switch (gateway.submit(seatIndex, candidate)) {
                case SubmissionOutcome.Accepted() -> {
                    return new Confirmation.Accepted(candidate, false);
                }
                case SubmissionOutcome.Rejected(var code) -> {
                    return new Confirmation.Rejected(code);
                }
                case SubmissionOutcome.Unacknowledged() -> {
                    var state = reconcile(seatIndex);
                    if (state instanceof Reconciliation.Accepted(var accepted)) {
                        return new Confirmation.Accepted(accepted, true);
                    }
                    if (state instanceof Reconciliation.Pending) {
                        return new Confirmation.Pending();
                    }
                }
            }
        }
        return new Confirmation.NotAccepted();
    }

    /** Reads accepted state, polling a bounded number of times while it is not yet current. */
    private Reconciliation reconcile(int seatIndex) {
        var state = gateway.reconcile(seatIndex);
        for (var poll = 1; poll < MAX_PENDING_POLLS && state instanceof Reconciliation.Pending; poll++) {
            state = gateway.reconcile(seatIndex);
        }
        return state;
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

    private sealed interface Confirmation {
        record Accepted(Candidate candidate, boolean recovered) implements Confirmation {}

        record Rejected(String code) implements Confirmation {}

        /** Accepted state is current and shows nothing was spent, even after the one allowed resubmission. */
        record NotAccepted() implements Confirmation {}

        /** Accepted state never became current, so whether the submission was spent is unknown. */
        record Pending() implements Confirmation {}
    }
}
