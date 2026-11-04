package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.CancelCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;

/** Cancels a campaign that is not over: its runs stop scheduling games and every result so far is kept. */
public class CancelCampaignCommandHandler implements CancelCampaignUseCase {

    private final CampaignUpdates updates;
    private final WaveRuns waveRuns;

    public CancelCampaignCommandHandler(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        this.updates = new CampaignUpdates(campaigns, clock);
        this.waveRuns = new WaveRuns(campaigns, runs);
    }

    @Override
    public Campaign handle(UUID campaignId) {
        var cancelled =
                updates.update(campaignId, (settled, now) -> new CampaignUpdates.Next(settled.cancel(now), false));
        waveRuns.stop(campaignId);
        return cancelled;
    }
}
