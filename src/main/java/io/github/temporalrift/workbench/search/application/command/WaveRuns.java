package io.github.temporalrift.workbench.search.application.command;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.StoredCampaign;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.wave.Wave;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

/**
 * Applies what a campaign says to the runs that play its waves and its confirmation. Pausing holds back every case
 * that has not started, and cases already executing finish; resuming and a change of parallelism allow that many cases
 * at once; stopping cancels the runs, the confirmation runs of a campaign included. Every call is safe to repeat and
 * ignores a run that is over.
 */
class WaveRuns {

    static final int HELD = 0;

    private final CampaignRepository campaigns;
    private final CampaignRuns runs;

    WaveRuns(CampaignRepository campaigns, CampaignRuns runs) {
        this.campaigns = campaigns;
        this.runs = runs;
    }

    void hold(UUID campaignId) {
        campaigns.waves(campaignId).forEach(wave -> runs.setConcurrency(wave.runId(), HELD));
    }

    void allow(UUID campaignId, int parallelism) {
        allow(campaigns.waves(campaignId), parallelism);
    }

    void allow(List<Wave> waves, int parallelism) {
        for (var wave : waves) {
            var progress = runs.progress(wave.runId());
            if (progress.isEmpty() || progress.get().phase().isFinal()) {
                continue;
            }
            if (progress.get().phase() == RunPhase.INTERRUPTED) {
                runs.resume(wave.runId());
            }
            runs.setConcurrency(wave.runId(), parallelism);
        }
    }

    /**
     * Lets the confirmation's current run play at the campaign's parallelism, then reads the campaign again: a change
     * of parallelism that landed after this campaign was read may just have been overwritten, and no later step would
     * correct it before more cases are claimed.
     */
    void allowConfirmation(Campaign campaign) {
        allowCurrentRun(campaign.confirmation(), campaign.parallelism());
        campaigns
                .find(campaign.campaignId())
                .map(StoredCampaign::campaign)
                .filter(now -> now.state() == CampaignState.CONFIRMING)
                .filter(now -> now.parallelism() != campaign.parallelism())
                .ifPresent(now -> allowCurrentRun(now.confirmation(), now.parallelism()));
    }

    /**
     * The first run that has not completed may play. The runs behind it stay held, and a run that is gone or ended
     * without completing releases nothing, because the campaign is about to fail over it.
     */
    private void allowCurrentRun(Confirmation confirmation, int parallelism) {
        for (var run : confirmation.runs()) {
            var progress = runs.progress(run.runId());
            if (progress.isEmpty()) {
                return;
            }
            var phase = progress.get().phase();
            if (phase == RunPhase.COMPLETED) {
                continue;
            }
            if (phase.isFinal()) {
                return;
            }
            if (phase == RunPhase.INTERRUPTED) {
                runs.resume(run.runId());
            }
            runs.setConcurrency(run.runId(), parallelism);
            return;
        }
    }

    void stop(UUID campaignId) {
        campaigns.waves(campaignId).forEach(wave -> runs.cancel(wave.runId()));
        campaigns
                .find(campaignId)
                .map(stored -> stored.campaign().confirmation())
                .ifPresent(confirmation -> confirmation.runs().forEach(run -> runs.cancel(run.runId())));
    }
}
