package io.github.temporalrift.workbench.search.domain.campaign;

import java.util.UUID;

/** What an accepted command on a campaign claimed its idempotency key for. */
public record CampaignCommandClaim(UUID campaignId, String requestHash) {}
