package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

/**
 * The frozen inputs of a configuration search.
 *
 * @param baseline the rules and content artifact references every candidate builds on, as the contract states them
 * @param policies the policies every game plays, as an experiment manifest states them
 * @param playerCounts the player counts every candidate plays
 * @param budgetSeconds the active time the search may spend
 * @param searchMasterSeed derives every search seed, as a decimal unsigned 64-bit integer
 * @param confirmationMasterSeed derives every confirmation seed
 */
public record CampaignDefinition(
        String name,
        JsonNode baseline,
        int parameterSpaceVersion,
        int criterionVersion,
        JsonNode policies,
        List<Integer> playerCounts,
        int budgetSeconds,
        String searchMasterSeed,
        String confirmationMasterSeed,
        SearchAlgorithm algorithm) {

    public static final int DEFAULT_BUDGET_SECONDS = 14_400;
    public static final int DEFAULT_PARALLELISM = 4;
    public static final int MINIMUM_BUDGET_SECONDS = 60;

    public CampaignDefinition {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(algorithm, "algorithm");
        playerCounts = List.copyOf(playerCounts);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("name must be 1 to 120 characters");
        }
        if (budgetSeconds < MINIMUM_BUDGET_SECONDS) {
            throw new IllegalArgumentException("budgetSeconds must be at least " + MINIMUM_BUDGET_SECONDS);
        }
        if (playerCounts.isEmpty()) {
            throw new IllegalArgumentException("a campaign plays at least one player count");
        }
    }

    /** The digest of the rules artifact every candidate's overrides apply to. */
    public String baselineRulesDigest() {
        return baseline.path("rulesArtifact").path("digest").asString();
    }
}
