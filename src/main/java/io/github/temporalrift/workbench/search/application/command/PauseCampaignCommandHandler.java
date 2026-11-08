package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.PauseCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCommandKey;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;

/** Pauses a campaign: its budget stops counting and no new game starts; games in flight finish. */
public class PauseCampaignCommandHandler implements PauseCampaignUseCase {

    private final CampaignUpdates updates;
    private final WaveRuns waveRuns;

    public PauseCampaignCommandHandler(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        this.updates = new CampaignUpdates(campaigns, clock);
        this.waveRuns = new WaveRuns(campaigns, runs);
    }

    @Override
    public Campaign handle(UUID campaignId, UUID idempotencyKey) {
        var command = CampaignCommandKey.of(idempotencyKey, "pauseCampaign", campaignId, "");
        var campaign =
                updates.update(campaignId, command, (settled, now) -> new CampaignUpdates.Next(settled.pause(), false));
        waveRuns.reconcile(campaignId);
        return campaign;
    }
}
