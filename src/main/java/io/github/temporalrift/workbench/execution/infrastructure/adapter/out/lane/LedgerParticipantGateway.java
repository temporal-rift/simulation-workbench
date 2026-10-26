package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Clock;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotResolution;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;
import io.github.temporalrift.workbench.policy.domain.decision.Reconciliation;
import io.github.temporalrift.workbench.policy.domain.decision.SubmissionOutcome;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/**
 * Makes participant commands durable: the intent is recorded before anything is sent, so a decision slot
 * is never sent twice. A slot whose acknowledgement was lost is reconciled against the service's accepted
 * state, and only a slot confirmed absent from current accepted state may be sent again. The accepted
 * slots are the case's decision transcript, and every command sent is retained as evidence with the
 * observation it was decided from.
 */
public class LedgerParticipantGateway implements ParticipantGateway {

    private final ParticipantGateway delegate;
    private final CommandLedger ledger;
    private final StepRecorder recorder;
    private final UUID caseId;
    private final UUID attemptId;
    private final Clock clock;
    private final Map<Integer, EntitledObservation> observed = new ConcurrentHashMap<>();

    public LedgerParticipantGateway(
            ParticipantGateway delegate,
            CommandLedger ledger,
            StepRecorder recorder,
            UUID caseId,
            UUID attemptId,
            Clock clock) {
        this.delegate = delegate;
        this.ledger = ledger;
        this.recorder = recorder;
        this.caseId = caseId;
        this.attemptId = attemptId;
        this.clock = clock;
    }

    @Override
    public EntitledObservation observe(int seatIndex) {
        var observation = delegate.observe(seatIndex);
        observed.put(seatIndex, observation);
        return observation;
    }

    @Override
    public SubmissionOutcome submit(int seatIndex, Candidate candidate) {
        if (candidate instanceof Candidate.ConfirmReady) {
            recorder.sending(observed.get(seatIndex), candidate);
            var outcome = delegate.submit(seatIndex, candidate);
            retain(seatIndex, outcome);
            return outcome;
        }
        var slot = slot(seatIndex);
        var intent = ledger.begin(slot, attemptId, CandidateCodec.encode(candidate), clock.instant());
        return switch (intent) {
            case CommandIntent.AlreadyAccepted _ -> new SubmissionOutcome.Accepted();
            case CommandIntent.InDoubt _ -> new SubmissionOutcome.Unacknowledged();
            case CommandIntent.Send _ -> send(slot, seatIndex, candidate);
        };
    }

    @Override
    public Reconciliation reconcile(int seatIndex) {
        var reconciliation = delegate.reconcile(seatIndex);
        var observation = observed.get(seatIndex);
        if (observation == null) {
            return reconciliation;
        }
        var slot = slot(seatIndex);
        switch (reconciliation) {
            case Reconciliation.Accepted(var acceptedCandidate) -> {
                // Terminal readiness has no decision slot, but its step is resolved all the same.
                if (!(acceptedCandidate instanceof Candidate.ConfirmReady)) {
                    ledger.resolve(slot, new SlotResolution.Recovered(), clock.instant());
                }
                recorder.resolved(seatIndex, slot.window(), StepOutcome.ACCEPTED);
            }
            case Reconciliation.NotAccepted _ ->
                ledger.find(slot)
                        .filter(held -> held.status() == SlotStatus.SENT)
                        .ifPresent(held -> {
                            ledger.resolve(slot, new SlotResolution.AbsentFromAcceptedState(), clock.instant());
                            recorder.resolved(seatIndex, slot.window(), StepOutcome.NOT_SPENT);
                        });
            default -> {
                // Pending: neither conclusion may be drawn, so the slot stays in doubt.
            }
        }
        return reconciliation;
    }

    private SubmissionOutcome send(SlotId slot, int seatIndex, Candidate candidate) {
        recorder.sending(observed.get(seatIndex), candidate);
        var outcome = delegate.submit(seatIndex, candidate);
        switch (outcome) {
            case SubmissionOutcome.Accepted _ -> ledger.resolve(slot, new SlotResolution.Accepted(), clock.instant());
            case SubmissionOutcome.Rejected(var code) ->
                ledger.resolve(slot, new SlotResolution.Rejected(code), clock.instant());
            case SubmissionOutcome.Unacknowledged _ -> {
                // The slot stays SENT: the service may hold the command, so it is reconciled before any resend.
            }
        }
        retain(seatIndex, outcome);
        return outcome;
    }

    /** Settles the step sent just before; a lost answer leaves it unacknowledged until accepted state is read. */
    private void retain(int seatIndex, SubmissionOutcome outcome) {
        var window = observed.get(seatIndex).window().ref();
        switch (outcome) {
            case SubmissionOutcome.Accepted _ -> recorder.resolved(seatIndex, window, StepOutcome.ACCEPTED);
            case SubmissionOutcome.Rejected(var code) ->
                recorder.resolved(seatIndex, window, StepOutcome.REJECTED, code);
            case SubmissionOutcome.Unacknowledged _ -> {
                // The answer is unknown: the step stays unacknowledged.
            }
        }
    }

    private SlotId slot(int seatIndex) {
        var observation = observed.get(seatIndex);
        if (observation == null) {
            throw new IllegalStateException("Seat " + seatIndex + " submitted without observing a window");
        }
        return new SlotId(caseId, seatIndex, observation.window().ref());
    }
}
