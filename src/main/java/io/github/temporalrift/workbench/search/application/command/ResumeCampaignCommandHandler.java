package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.ResumeCampaignUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;

/** Resumes a paused campaign: its budget counts again from now and its games are allowed again. */
public class ResumeCampaignCommandHandler implements ResumeCampaignUseCase {

    private final CampaignUpdates updates;
    private final WaveRuns waveRuns;

    public ResumeCampaignCommandHandler(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        this.updates = new CampaignUpdates(campaigns, clock);
        this.waveRuns = new WaveRuns(campaigns, runs);
    }

    @Override
    public Campaign handle(UUID campaignId) {
        var resumed = updates.update(campaignId, (settled, now) -> {
            var next = settled.resume();
            return new CampaignUpdates.Next(next, settled.state() != CampaignState.RUNNING);
        });
        waveRuns.allow(campaignId, resumed.parallelism());
        return resumed;
    }
}
