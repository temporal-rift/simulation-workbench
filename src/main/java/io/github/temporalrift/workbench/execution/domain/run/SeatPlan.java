package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Map;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * One seat of a case: its faction and the policy that plays it.
 *
 * @param policyDigest the artifact digest of the policy entry the seat plays; null in a plan stored before parameter
 *     sets existed, where the id and version alone name it
 * @param policyParameters the parameters that entry names, empty for a bundle that declares none
 */
public record SeatPlan(
        int seatIndex,
        Faction faction,
        String policyId,
        String policyVersion,
        String policyDigest,
        Map<String, Integer> policyParameters) {

    public SeatPlan {
        policyParameters = policyParameters == null ? Map.of() : Map.copyOf(policyParameters);
    }

    public SeatPlan(int seatIndex, Faction faction, String policyId, String policyVersion) {
        this(seatIndex, faction, policyId, policyVersion, null, Map.of());
    }
}
