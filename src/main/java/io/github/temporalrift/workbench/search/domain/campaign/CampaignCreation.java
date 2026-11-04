package io.github.temporalrift.workbench.search.domain.campaign;

/** What storing a queued campaign led to. */
public sealed interface CampaignCreation {

    /** The campaign was stored. */
    record Created() implements CampaignCreation {}

    /** The idempotency key already belonged to an earlier request. */
    record Existing(CampaignClaim claim) implements CampaignCreation {}
}
