package io.github.temporalrift.workbench.experiment;

import io.github.temporalrift.workbench.shared.game.Faction;

/** One seat of a case coordinate: its faction and the policy that plays it. */
public record ExperimentSeat(int seatIndex, Faction faction, String policyId, String policyVersion) {}
