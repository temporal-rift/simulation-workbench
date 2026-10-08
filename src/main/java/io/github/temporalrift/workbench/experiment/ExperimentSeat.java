package io.github.temporalrift.workbench.experiment;

/** One seat of a case coordinate: its faction and the policy that plays it. */
public record ExperimentSeat(int seatIndex, String faction, String policyId, String policyVersion) {}
