package io.github.temporalrift.workbench.execution.domain.command;

import java.util.UUID;

/** One seat's decision in one window of one case: the unit that is sent at most once. */
public record SlotId(UUID caseId, int seatIndex, String windowKey) {}
