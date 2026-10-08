package io.github.temporalrift.workbench.execution.support;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;

/** A map-backed ledger with the same slot semantics as the PostgreSQL one, for tests without a database. */
public class InMemoryCommandLedger implements CommandLedger {

    private final Map<SlotId, Slot> slots = new ConcurrentHashMap<>();

    @Override
    public synchronized CommandIntent begin(SlotId slot, UUID attemptId, String request, Instant now) {
        var existing = slots.get(slot);
        if (existing == null || existing.status() == SlotStatus.NOT_SPENT) {
            slots.put(slot, new Slot(slot, attemptId, request, SlotStatus.SENT, null));
            return new CommandIntent.Send();
        }
        return existing.status() == SlotStatus.ACCEPTED
                ? new CommandIntent.AlreadyAccepted(existing)
                : new CommandIntent.InDoubt(existing);
    }

    @Override
    public synchronized void resolve(SlotId slot, SlotStatus status, String outcome, Instant now) {
        var existing = slots.get(slot);
        if (existing != null) {
            slots.put(slot, new Slot(slot, existing.attemptId(), existing.request(), status, outcome));
        }
    }

    @Override
    public Optional<Slot> find(SlotId slot) {
        return Optional.ofNullable(slots.get(slot));
    }

    @Override
    public List<Slot> accepted(UUID caseId) {
        return with(caseId, SlotStatus.ACCEPTED);
    }

    @Override
    public List<Slot> inDoubt(UUID caseId) {
        return with(caseId, SlotStatus.SENT);
    }

    @Override
    public synchronized void reset(UUID caseId) {
        slots.keySet().removeIf(slot -> slot.caseId().equals(caseId));
    }

    /** Pre-populates a slot, as a previous attempt would have left it. */
    public synchronized void put(SlotId slot, UUID attemptId, String request, SlotStatus status) {
        slots.put(slot, new Slot(slot, attemptId, request, status, null));
    }

    private List<Slot> with(UUID caseId, SlotStatus status) {
        return slots.values().stream()
                .filter(slot -> slot.id().caseId().equals(caseId) && slot.status() == status)
                .sorted(Comparator.comparing((Slot slot) -> slot.id().windowKey())
                        .thenComparingInt(slot -> slot.id().seatIndex()))
                .toList();
    }
}
