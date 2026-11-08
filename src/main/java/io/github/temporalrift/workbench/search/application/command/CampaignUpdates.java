package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCommandKey;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;

/**
 * Changes a campaign on a designer request, once per idempotency key. The key is claimed in the write that applies the
 * change, so a request refused or lost before that write claims nothing, and one whose write was stored is answered
 * with the campaign when it is repeated. The active time since the campaign last step is counted first, so a pause or
 * a cancel stops the budget exactly there, and the write is repeated from a fresh read when the worker or a request
 * with the same key wrote first.
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

    Campaign update(UUID campaignId, CampaignCommandKey command, Change change) {
        for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
            var replayed = replayed(campaignId, command);
            if (replayed.isPresent()) {
                return replayed.get();
            }
            var stored = campaigns.find(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
            var now = clock.instant();
            var settled = stored.settledAt(now);
            var next = next(campaignId, command, change, settled.campaign(), now);
            if (next.isEmpty()) {
                return replayed(campaignId, command).orElseThrow();
            }
            var accruedAt = next.get().startsCounting() ? now : settled.accruedAt();
            if (campaigns.apply(
                    CampaignChange.of(stored, next.get().campaign(), accruedAt).claiming(command))) {
                return next.get().campaign();
            }
        }
        throw new IllegalStateException("Campaign " + campaignId + " keeps changing under the request");
    }

    /**
     * What the change makes of the campaign, or empty when it refuses a state that a request with this same key brought
     * about since the key was last read, which answers for this one.
     */
    private Optional<Next> next(
            UUID campaignId, CampaignCommandKey command, Change change, Campaign settled, Instant now) {
        try {
            return Optional.of(change.apply(settled, now));
        } catch (InvalidCampaignStateException e) {
            if (replayed(campaignId, command).isPresent()) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * The campaign as it stands when an earlier request claimed the key with this very request, or empty when the key
     * is free.
     *
     * @throws CampaignIdempotencyConflictException when another request claimed it
     */
    Optional<Campaign> replayed(UUID campaignId, CampaignCommandKey command) {
        var claimed = campaigns.claimedRequestHash(command.idempotencyKey());
        if (claimed.isEmpty()) {
            return Optional.empty();
        }
        if (!claimed.get().equals(command.requestHash())) {
            throw new CampaignIdempotencyConflictException(command.idempotencyKey());
        }
        return Optional.of(campaigns
                .find(campaignId)
                .orElseThrow(() -> new CampaignNotFoundException(campaignId))
                .settledAt(clock.instant())
                .campaign());
    }
}
