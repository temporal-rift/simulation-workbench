package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.Slot;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

/** Command ledger: one row per decision slot, claimed atomically before anything is sent. */
@Component
public class CommandLedgerAdapter implements CommandLedger {

    /** Window keys are ASCII, so their natural order is the byte order the ledger always listed them in. */
    private static final Comparator<Slot> SLOT_ORDER = Comparator.<Slot, String>comparing(
                    slot -> slot.id().windowKey())
            .thenComparingInt(slot -> slot.id().seatIndex());

    private final CaseCommandJpaRepository commands;
    private final InsertOnce insertOnce;
    private final CommandSlots slots;

    CommandLedgerAdapter(CaseCommandJpaRepository commands, InsertOnce insertOnce, CommandSlots slots) {
        this.commands = commands;
        this.insertOnce = insertOnce;
        this.slots = slots;
    }

    @Override
    public CommandIntent begin(SlotId slot, UUID attemptId, String request, Instant now) {
        var key = new CaseCommandJpaEntity.Key(slot.caseId(), slot.seatIndex(), slot.windowKey());
        if (!commands.existsById(key)
                && insertOnce.insert(() -> commands.saveAndFlush(new CaseCommandJpaEntity(
                        slot.caseId(), slot.seatIndex(), slot.windowKey(), attemptId, request, "SENT", now)))) {
            return new CommandIntent.Send();
        }
        return slots.decide(slot, attemptId, request, now);
    }

    @Override
    @Transactional
    public void resolve(SlotId slot, SlotStatus status, String outcome, Instant now) {
        commands.findByCaseIdAndSeatIndexAndWindowKey(slot.caseId(), slot.seatIndex(), slot.windowKey())
                .ifPresent(command -> command.resolve(status.name(), outcome, now));
    }

    @Override
    public Optional<Slot> find(SlotId slot) {
        return commands.findByCaseIdAndSeatIndexAndWindowKey(slot.caseId(), slot.seatIndex(), slot.windowKey())
                .map(CommandLedgerAdapter::slot);
    }

    @Override
    public List<Slot> accepted(UUID caseId) {
        return withStatus(caseId, SlotStatus.ACCEPTED);
    }

    @Override
    public List<Slot> inDoubt(UUID caseId) {
        return withStatus(caseId, SlotStatus.SENT);
    }

    @Override
    @Transactional
    public void reset(UUID caseId) {
        commands.deleteAllByCaseId(caseId);
    }

    private List<Slot> withStatus(UUID caseId, SlotStatus status) {
        return commands.findAllByCaseIdAndStatus(caseId, status.name()).stream()
                .map(CommandLedgerAdapter::slot)
                .sorted(SLOT_ORDER)
                .toList();
    }

    static Slot slot(CaseCommandJpaEntity command) {
        return new Slot(
                new SlotId(command.caseId(), command.seatIndex(), command.windowKey()),
                command.attemptId(),
                command.request(),
                SlotStatus.valueOf(command.status()),
                command.outcome());
    }
}
