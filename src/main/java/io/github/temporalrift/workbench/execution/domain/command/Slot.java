package io.github.temporalrift.workbench.execution.domain.command;

import java.util.UUID;

/** A recorded decision slot: the command intended for it, which attempt sent it, and what is known of it. */
public record Slot(SlotId id, UUID attemptId, String request, SlotStatus status, String outcome) {}
