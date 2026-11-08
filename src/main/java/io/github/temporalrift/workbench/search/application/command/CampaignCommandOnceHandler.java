package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.function.Supplier;

import io.github.temporalrift.workbench.search.application.port.in.CampaignCommandOnceUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * Holds a campaign command to its idempotency key. The key is claimed once the command is accepted, so a client that
 * lost the answer gets the campaign back instead of having the command applied again or refused for the state it
 * brought about. A key that created a campaign, or that another request claimed, conflicts.
 */
public class CampaignCommandOnceHandler implements CampaignCommandOnceUseCase {

    private final CampaignRepository campaigns;
    private final Clock clock;

    public CampaignCommandOnceHandler(CampaignRepository campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    @Override
    public Campaign handle(Command command, Supplier<Campaign> apply) {
        var key = command.idempotencyKey();
        if (campaigns.findClaim(key).isPresent()) {
            throw new CampaignIdempotencyConflictException(key);
        }
        var hash = Sha256.hexUtf8(command.operation() + ":" + command.campaignId() + ":" + command.body());
        var claimed = campaigns.findCommand(key);
        if (claimed.isPresent()) {
            if (!claimed.get().requestHash().equals(hash)) {
                throw new CampaignIdempotencyConflictException(key);
            }
            return campaigns
                    .find(command.campaignId())
                    .orElseThrow(() -> new CampaignNotFoundException(command.campaignId()))
                    .settledAt(clock.instant())
                    .campaign();
        }
        var applied = apply.get();
        campaigns.claimCommand(key, command.campaignId(), hash, clock.instant());
        return applied;
    }
}
