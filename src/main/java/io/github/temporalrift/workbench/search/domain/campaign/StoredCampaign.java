package io.github.temporalrift.workbench.search.domain.campaign;

import java.time.Instant;

/**
 * A campaign as stored: its row version, which a write must present to prove nothing else wrote since, and the
 * moment its active time was last counted up to.
 */
public record StoredCampaign(Campaign campaign, long version, Instant accruedAt) {}
