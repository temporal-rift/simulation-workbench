package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.github.temporalrift.workbench.execution.domain.command.CommandIntent;
import io.github.temporalrift.workbench.execution.domain.command.SlotId;
import io.github.temporalrift.workbench.execution.domain.command.SlotStatus;

/** Decides what to do with a slot that is already claimed, under the slot's row lock. */
@Component
class CommandSlots {

    private final CaseCommandJpaRepository commands;

    CommandSlots(CaseCommandJpaRepository commands) {
        this.commands = commands;
    }

    @Transactional
    CommandIntent decide(SlotId slot, UUID attemptId, String request, Instant now) {
        var command = commands.findWithLockByCaseIdAndSeatIndexAndWindowKey(
                        slot.caseId(), slot.seatIndex(), slot.windowKey())
                .orElseThrow(() -> new IllegalStateException("Command slot vanished: " + slot));
        return switch (SlotStatus.valueOf(command.status())) {
            case ACCEPTED -> new CommandIntent.AlreadyAccepted(CommandLedgerAdapter.slot(command));
            case SENT -> new CommandIntent.InDoubt(CommandLedgerAdapter.slot(command));
            case NOT_SPENT -> {
                command.resend(attemptId, request, now);
                yield new CommandIntent.Send();
            }
        };
    }
}
