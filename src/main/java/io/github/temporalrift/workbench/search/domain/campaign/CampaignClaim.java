package io.github.temporalrift.workbench.search.domain.campaign;

/** The campaign an idempotency key was claimed for, with the identity of the request that claimed it. */
public record CampaignClaim(Campaign campaign, String requestHash) {}
