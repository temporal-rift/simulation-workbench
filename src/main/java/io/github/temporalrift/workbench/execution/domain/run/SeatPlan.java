package io.github.temporalrift.workbench.execution.domain.run;

/** One seat of a case: its faction and the policy that plays it. */
public record SeatPlan(int seatIndex, String faction, String policyId, String policyVersion) {}
