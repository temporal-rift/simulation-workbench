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
import io.github.temporalrift.workbench.execution.domain.command.SlotResolution;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/** Command ledger: one row per decision slot, claimed atomically before anything is sent. */
@Component
public class CommandLedgerAdapter implements CommandLedger {

    private static final Comparator<Slot> SLOT_ORDER = Comparator.<Slot, WindowRef>comparing(
                    slot -> slot.id().window())
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
        if (!commands.existsById(CaseCommandJpaEntity.Key.of(slot))
                && insertOnce.insert(() -> commands.saveAndFlush(
                        new CaseCommandJpaEntity(slot, attemptId, request, SlotStatus.SENT, now)))) {
            return new CommandIntent.Send();
        }
        return slots.decide(slot, attemptId, request, now);
    }

    @Override
    @Transactional
    public void resolve(SlotId slot, SlotResolution resolution, Instant now) {
        commands.findById(CaseCommandJpaEntity.Key.of(slot)).ifPresent(command -> command.resolve(resolution, now));
    }

    @Override
    public Optional<Slot> find(SlotId slot) {
        return commands.findById(CaseCommandJpaEntity.Key.of(slot)).map(CommandLedgerAdapter::slot);
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
                command.slot(),
                command.attemptId(),
                command.request(),
                SlotStatus.valueOf(command.status()),
                command.resolution());
    }
}
