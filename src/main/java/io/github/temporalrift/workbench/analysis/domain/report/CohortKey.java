package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.SeatPolicy;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A cohort of a variant, table and player count; a null faction set pools every faction set of the player count
 * and a null seat pools every seat.
 *
 * @param seatPolicies the policy of each seat of the table at this player count, indexed by seat
 */
public record CohortKey(
        String variantLabel,
        String tableId,
        List<SeatPolicy> seatPolicies,
        int playerCount,
        List<Faction> factionSet,
        Integer seatIndex) {

    public CohortKey {
        Objects.requireNonNull(variantLabel, "variantLabel");
        Objects.requireNonNull(tableId, "tableId");
        seatPolicies = List.copyOf(seatPolicies);
        factionSet = factionSet == null ? null : List.copyOf(factionSet);
    }

    /** The cohort that pools every faction set and seat of a case's variant, table and player count. */
    public static CohortKey pooledOf(AnalyzedCase analyzedCase) {
        return new CohortKey(
                analyzedCase.variantLabel(),
                analyzedCase.tableId(),
                analyzedCase.seatPolicies(),
                analyzedCase.playerCount(),
                null,
                null);
    }

    /** The id of the policy that plays the cohort's seats; null when they play more than one. */
    public String policyId() {
        var policy = policy();
        return policy == null ? null : policy.id();
    }

    /** The version of the policy that plays the cohort's seats; null when they play more than one. */
    public String policyVersion() {
        var policy = policy();
        return policy == null ? null : policy.version();
    }

    private SeatPolicy policy() {
        if (seatIndex != null) {
            return seatPolicies.get(seatIndex);
        }
        var first = seatPolicies.getFirst();
        return seatPolicies.stream().allMatch(first::equals) ? first : null;
    }

    public boolean contains(AnalyzedCase analyzedCase) {
        return variantLabel.equals(analyzedCase.variantLabel())
                && tableId.equals(analyzedCase.tableId())
                && playerCount == analyzedCase.playerCount()
                && (factionSet == null || factionSet.equals(factionSetOf(analyzedCase)));
    }

    /** The case's factions sorted by name, as the matrix names its faction sets. */
    public static List<Faction> factionSetOf(AnalyzedCase analyzedCase) {
        return analyzedCase.seatFactions().stream()
                .sorted(Comparator.comparing(Faction::name))
                .toList();
    }

    /** The cohort that pools this cohort's seats. */
    public CohortKey withoutSeat() {
        return new CohortKey(variantLabel, tableId, seatPolicies, playerCount, factionSet, null);
    }

    public CohortKey withFactionSet(List<Faction> set) {
        return new CohortKey(variantLabel, tableId, seatPolicies, playerCount, set, seatIndex);
    }

    /** The same cohort of another variant. */
    public CohortKey withVariant(String label) {
        return new CohortKey(label, tableId, seatPolicies, playerCount, factionSet, seatIndex);
    }

    /** A stable text identity, used to seed the cohort's resampling. */
    public String text() {
        var version = policyVersion();
        return String.join(
                "|",
                variantLabel,
                tableId,
                version == null ? "*" : version,
                String.valueOf(playerCount),
                factionSet == null ? "*" : factionSet.toString(),
                seatIndex == null ? "*" : seatIndex.toString());
    }
}
