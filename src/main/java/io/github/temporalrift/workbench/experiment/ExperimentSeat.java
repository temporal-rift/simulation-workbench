package io.github.temporalrift.workbench.experiment;

import java.util.Map;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * One seat of a case coordinate: its faction and the policy that plays it.
 *
 * @param policyDigest the artifact digest of the policy entry the seat plays; null when the id and version alone name
 *     it
 * @param policyParameters the parameters of that entry, empty for a bundle that declares none
 */
public record ExperimentSeat(
        int seatIndex,
        Faction faction,
        String policyId,
        String policyVersion,
        String policyDigest,
        Map<String, Integer> policyParameters) {

    public ExperimentSeat {
        policyParameters = policyParameters == null ? Map.of() : Map.copyOf(policyParameters);
    }

    public ExperimentSeat(int seatIndex, Faction faction, String policyId, String policyVersion) {
        this(seatIndex, faction, policyId, policyVersion, null, Map.of());
    }
}
