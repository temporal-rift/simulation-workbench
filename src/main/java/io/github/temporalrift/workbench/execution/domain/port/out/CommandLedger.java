package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;

/**
 * Durable record of the commands sent to the real services, one per decision slot: a seat's decision in
 * one window of one case. A slot that was sent but never acknowledged is in doubt and is reconciled
 * against the service's accepted state instead of being sent again, so a lost response cannot spend it
 * twice. The accepted slots are the case's decision transcript, whichever attempts produced them.
 */
public interface CommandLedger {

    /**
     * Records the intent to send into a slot. The intent is recorded only when the slot is free or its
     * previous command was definitively not spent; otherwise the existing record decides.
     */
    CommandIntent begin(SlotId slot, UUID attemptId, String request, Instant now);

    /** Resolves the slot after the service answered or accepted state was reconciled. */
    void resolve(SlotId slot, SlotStatus status, String outcome, Instant now);

    Optional<Slot> find(SlotId slot);

    /** Every accepted decision of the case in canonical order (window key, then seat). */
    List<Slot> accepted(UUID caseId);

    /** The slots that were sent and never resolved. */
    List<Slot> inDoubt(UUID caseId);

    /**
     * Forgets the case's slots. Used when the case starts a new game on a fresh lane: the slots of the
     * abandoned game must neither block nor appear in the transcript of the new one.
     */
    void reset(UUID caseId);
}
