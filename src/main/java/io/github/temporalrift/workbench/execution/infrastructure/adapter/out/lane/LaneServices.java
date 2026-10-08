package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;

/** What a lane records into while it plays: the command ledger, the evidence ledger, and the event observers. */
public record LaneServices(CommandLedger commands, EvidenceLedger evidence, GameEventObservers observers) {}
