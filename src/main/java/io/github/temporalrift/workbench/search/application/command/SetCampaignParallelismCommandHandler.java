package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.UUID;

import io.github.temporalrift.workbench.search.application.port.in.SetCampaignParallelismUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignCommandKey;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;

/** Changes how many games a campaign plays at once, from the next game it claims. */
public class SetCampaignParallelismCommandHandler implements SetCampaignParallelismUseCase {

    private final CampaignUpdates updates;
    private final WaveRuns waveRuns;

    public SetCampaignParallelismCommandHandler(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        this.updates = new CampaignUpdates(campaigns, clock);
        this.waveRuns = new WaveRuns(campaigns, runs);
    }

    @Override
    public Campaign handle(UUID campaignId, int parallelism, UUID idempotencyKey) {
        var command = CampaignCommandKey.of(
                idempotencyKey, "setCampaignParallelism", campaignId, String.valueOf(parallelism));
        var changed = updates.update(
                campaignId,
                command,
                (settled, now) -> new CampaignUpdates.Next(settled.withParallelism(parallelism), false));
        if (changed.state() == CampaignState.RUNNING) {
            waveRuns.allow(campaignId, changed.parallelism());
        } else if (changed.state() == CampaignState.CONFIRMING) {
            waveRuns.allowConfirmation(changed);
        }
        return changed;
    }
}
