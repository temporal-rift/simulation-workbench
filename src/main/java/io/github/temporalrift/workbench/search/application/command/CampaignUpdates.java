package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;

/**
 * Changes a campaign on a designer request. The active time since the campaign last step is counted first, so a
 * pause or a cancel stops the budget exactly there, and the write is repeated from a fresh read when the worker wrote
 * first.
 */
class CampaignUpdates {

    private static final int ATTEMPTS = 5;

    private final CampaignRepository campaigns;
    private final Clock clock;

    CampaignUpdates(CampaignRepository campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    /** What a change makes of the campaign, settled up to now. */
    @FunctionalInterface
    interface Change {

        Next apply(Campaign settled, Instant now);
    }

    /**
     * @param startsCounting whether active time is counted from now on, rather than from where it was counted to
     */
    record Next(Campaign campaign, boolean startsCounting) {}

    Campaign update(UUID campaignId, Change change) {
        for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
            var stored = campaigns.find(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
            var now = clock.instant();
            var settled = stored.settledAt(now);
            var next = change.apply(settled.campaign(), now);
            var accruedAt = next.startsCounting() ? now : settled.accruedAt();
            if (next.campaign().equals(stored.campaign()) && accruedAt.equals(stored.accruedAt())) {
                return stored.campaign();
            }
            if (campaigns.save(next.campaign(), accruedAt, stored.version())) {
                return next.campaign();
            }
        }
        throw new IllegalStateException("Campaign " + campaignId + " keeps changing under the request");
    }
}
