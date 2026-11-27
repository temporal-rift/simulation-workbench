package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.List;
import java.util.Objects;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The frozen inputs of a configuration search.
 *
 * @param baseline the rules and content artifact references every candidate builds on, as the contract states them
 * @param policies the policies every game plays, as an experiment manifest states them
 * @param playerCounts the player counts every candidate plays
 * @param budgetSeconds the active time a version 1 search was allowed to spend, as it recorded it; null for version 2,
 *     whose search ends by its plan and has no time limit
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
        Integer budgetSeconds,
        String searchMasterSeed,
        String confirmationMasterSeed,
        SearchAlgorithm algorithm,
        CampaignKind kind,
        Faction faction,
        Integer policyParameterSpaceVersion) {

    /** The definition of a rules search. */
    public CampaignDefinition(
            String name,
            JsonNode baseline,
            int parameterSpaceVersion,
            int criterionVersion,
            JsonNode policies,
            List<Integer> playerCounts,
            Integer budgetSeconds,
            String searchMasterSeed,
            String confirmationMasterSeed,
            SearchAlgorithm algorithm) {
        this(
                name,
                baseline,
                parameterSpaceVersion,
                criterionVersion,
                policies,
                playerCounts,
                budgetSeconds,
                searchMasterSeed,
                confirmationMasterSeed,
                algorithm,
                CampaignKind.RULES_SEARCH,
                null,
                null);
    }

    public static final int DEFAULT_PARALLELISM = 4;
    private static final int MINIMUM_BUDGET_SECONDS = 60;

    public CampaignDefinition {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(policies, "policies");
        Objects.requireNonNull(algorithm, "algorithm");
        playerCounts = List.copyOf(playerCounts);
        kind = kind == null ? CampaignKind.RULES_SEARCH : kind;
        requireKindInputs(kind, faction, policyParameterSpaceVersion, playerCounts);
        if (name == null || name.isBlank() || name.length() > 120) {
            throw new IllegalArgumentException("name must be 1 to 120 characters");
        }
        if (algorithm.version() == SearchAlgorithm.Version.SUCCESSIVE_HALVING_V1) {
            if (budgetSeconds == null || budgetSeconds < MINIMUM_BUDGET_SECONDS) {
                throw new IllegalArgumentException(
                        "a version 1 search records a budget of at least " + MINIMUM_BUDGET_SECONDS + " seconds");
            }
        } else if (budgetSeconds != null) {
            throw new IllegalArgumentException("a version 2 search has no time budget");
        }
        if (playerCounts.isEmpty()) {
            throw new IllegalArgumentException("a campaign plays at least one player count");
        }
    }

    /** A hunt names its faction, its policy parameter space and exactly one player count; a rules search names none. */
    private static void requireKindInputs(
            CampaignKind kind, Faction faction, Integer policyParameterSpaceVersion, List<Integer> playerCounts) {
        if (kind != CampaignKind.POLICY_HUNT) {
            if (faction != null || policyParameterSpaceVersion != null) {
                throw new IllegalArgumentException("only a policy hunt names a faction or a policy parameter space");
            }
            return;
        }
        if (faction == null || policyParameterSpaceVersion == null) {
            throw new IllegalArgumentException("a policy hunt names its faction and policy parameter space");
        }
        if (playerCounts.size() != 1) {
            throw new IllegalArgumentException("a policy hunt plays exactly one player count");
        }
    }

    /** Whether this is a policy hunt. */
    public boolean isHunt() {
        return kind == CampaignKind.POLICY_HUNT;
    }

    /** The player count of a hunt. */
    public int huntPlayerCount() {
        return playerCounts.getFirst();
    }

    /**
     * The seeds every candidate of {@code round} has played once the round is done: the algorithm's, or the hunt
     * plan's, whose last round is capped at the games it needs rather than the next power of {@code eta}.
     */
    public int seedsInRound(int round) {
        return isHunt() ? HuntPlan.seedsInRound(round, huntPlayerCount()) : algorithm.seedsInRound(round);
    }

    /** The digest of the rules artifact every candidate's overrides apply to. */
    public String baselineRulesDigest() {
        return baseline.path("rulesArtifact").path("digest").asString();
    }
}
