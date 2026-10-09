package io.github.temporalrift.workbench.analysis.domain.report;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;

/**
 * A cohort of a variant, policy and player count; a null faction set pools every faction set of the player count
 * and a null seat pools every seat.
 */
public record CohortKey(
        String variantLabel,
        String policyId,
        String policyVersion,
        int playerCount,
        List<Faction> factionSet,
        Integer seatIndex) {

    public CohortKey {
        Objects.requireNonNull(variantLabel, "variantLabel");
        Objects.requireNonNull(policyId, "policyId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        factionSet = factionSet == null ? null : List.copyOf(factionSet);
    }

    public boolean contains(AnalyzedCase analyzedCase) {
        return variantLabel.equals(analyzedCase.variantLabel())
                && policyId.equals(analyzedCase.policyId())
                && policyVersion.equals(analyzedCase.policyVersion())
                && playerCount == analyzedCase.playerCount()
                && (factionSet == null || factionSet.equals(factionSetOf(analyzedCase)));
    }

    /** The case's factions sorted by name, as the matrix names its faction sets. */
    public static List<Faction> factionSetOf(AnalyzedCase analyzedCase) {
        return analyzedCase.seatFactions().stream()
                .sorted(Comparator.comparing(Faction::name))
                .toList();
    }

    /** The same cohort of another variant. */
    public CohortKey withVariant(String label) {
        return new CohortKey(label, policyId, policyVersion, playerCount, factionSet, seatIndex);
    }

    /** A stable text identity, used to seed the cohort's resampling. */
    public String text() {
        return String.join(
                "|",
                variantLabel,
                policyId,
                policyVersion,
                String.valueOf(playerCount),
                factionSet == null ? "*" : factionSet.toString(),
                seatIndex == null ? "*" : seatIndex.toString());
    }
}
