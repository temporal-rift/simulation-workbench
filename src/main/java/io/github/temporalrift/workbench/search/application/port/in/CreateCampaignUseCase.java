package io.github.temporalrift.workbench.search.application.port.in;

import java.util.List;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;

public interface CreateCampaignUseCase {

    /**
     * Freezes the definition into a queued campaign, once per idempotency key: the same key with the same definition
     * returns the original campaign.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException when the definition
     *     cannot be searched
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException when the
     *     key created a campaign from another definition
     */
    Campaign handle(Command command);

    /**
     * @param budgetSeconds the active time the search may spend, or null for the default
     * @param parallelism games played at once, or null for the default
     */
    record Command(
            UUID idempotencyKey,
            String name,
            JsonNode baseline,
            int parameterSpaceVersion,
            int criterionVersion,
            JsonNode policies,
            List<Integer> playerCounts,
            Integer budgetSeconds,
            Integer parallelism,
            String searchMasterSeed,
            String confirmationMasterSeed,
            SearchAlgorithm algorithm) {}
}
