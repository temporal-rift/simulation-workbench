package io.github.temporalrift.workbench.execution.domain.run;

import io.github.temporalrift.workbench.shared.game.Faction;

/** One seat of a case: its faction and the policy that plays it. */
public record SeatPlan(int seatIndex, Faction faction, String policyId, String policyVersion) {}
