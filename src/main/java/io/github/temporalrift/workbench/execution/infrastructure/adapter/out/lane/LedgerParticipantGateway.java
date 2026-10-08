package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/**
 * Makes participant commands durable: the intent is recorded before anything is sent, so a decision slot
 * is never sent twice. A slot whose acknowledgement was lost is reconciled against the service's accepted
 * state, and only a slot confirmed absent from current accepted state may be sent again. The accepted
 * slots are the case's decision transcript.
 */
public class LedgerParticipantGateway implements ParticipantGateway {

    private final ParticipantGateway delegate;
    private final SlotReconciler reconciler;
    private final CommandLedger ledger;
    private final UUID caseId;
    private final UUID attemptId;
    private final Clock clock;
    private final Map<Integer, String> windows = new ConcurrentHashMap<>();

    public LedgerParticipantGateway(
            ParticipantGateway delegate,
            SlotReconciler reconciler,
            CommandLedger ledger,
            UUID caseId,
            UUID attemptId,
            Clock clock) {
        this.delegate = delegate;
        this.reconciler = reconciler;
        this.ledger = ledger;
        this.caseId = caseId;
        this.attemptId = attemptId;
        this.clock = clock;
    }

    /**
     * Resolves the slots a previous attempt sent without learning the answer, from the service's accepted
     * state. A slot whose answer cannot be read yet stays in doubt and blocks a resend.
     */
    public void recoverInDoubt() {
        for (var slot : ledger.inDoubt(caseId)) {
            reconciler
                    .holds(slot.id().seatIndex(), slot.id().windowKey())
                    .ifPresent(held -> ledger.resolve(
                            slot.id(),
                            held ? SlotStatus.ACCEPTED : SlotStatus.NOT_SPENT,
                            held ? "RECOVERED" : "ABSENT_FROM_ACCEPTED_STATE",
                            clock.instant()));
        }
    }

    @Override
    public EntitledObservation observe(int seatIndex) {
        var observation = delegate.observe(seatIndex);
        windows.put(seatIndex, observation.window().key());
        return observation;
    }

    @Override
    public SubmissionOutcome submit(int seatIndex, Candidate candidate) {
        if (candidate instanceof Candidate.ConfirmReady) {
            return delegate.submit(seatIndex, candidate);
        }
        var slot = slot(seatIndex);
        var intent = ledger.begin(slot, attemptId, candidate.toString(), clock.instant());
        return switch (intent) {
            case CommandIntent.AlreadyAccepted _ -> new SubmissionOutcome.Accepted();
            case CommandIntent.InDoubt _ -> new SubmissionOutcome.Unacknowledged();
            case CommandIntent.Send _ -> send(slot, seatIndex, candidate);
        };
    }

    @Override
    public Reconciliation reconcile(int seatIndex) {
        var reconciliation = delegate.reconcile(seatIndex);
        if (windows.get(seatIndex) == null) {
            return reconciliation;
        }
        var slot = slot(seatIndex);
        switch (reconciliation) {
            case Reconciliation.Accepted accepted
            when !(accepted.candidate() instanceof Candidate.ConfirmReady) ->
                ledger.resolve(slot, SlotStatus.ACCEPTED, "RECONCILED", clock.instant());
            case Reconciliation.NotAccepted _ ->
                ledger.find(slot)
                        .filter(held -> held.status() == SlotStatus.SENT)
                        .ifPresent(held -> ledger.resolve(
                                slot, SlotStatus.NOT_SPENT, "ABSENT_FROM_ACCEPTED_STATE", clock.instant()));
            default -> {
                // Pending: neither conclusion may be drawn, so the slot stays in doubt.
            }
        }
        return reconciliation;
    }

    private SubmissionOutcome send(SlotId slot, int seatIndex, Candidate candidate) {
        var outcome = delegate.submit(seatIndex, candidate);
        switch (outcome) {
            case SubmissionOutcome.Accepted _ -> ledger.resolve(slot, SlotStatus.ACCEPTED, "ACCEPTED", clock.instant());
            case SubmissionOutcome.Rejected(var code) ->
                ledger.resolve(slot, SlotStatus.NOT_SPENT, code, clock.instant());
            case SubmissionOutcome.Unacknowledged _ -> {
                // The slot stays SENT: the service may hold the command, so it is reconciled before any resend.
            }
        }
        return outcome;
    }

    private SlotId slot(int seatIndex) {
        var window = windows.get(seatIndex);
        if (window == null) {
            throw new IllegalStateException("Seat " + seatIndex + " submitted without observing a window");
        }
        return new SlotId(caseId, seatIndex, window);
    }
}
