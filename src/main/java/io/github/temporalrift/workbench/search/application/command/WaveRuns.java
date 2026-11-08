package io.github.temporalrift.workbench.search.application.command;

import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

/**
 * Makes the runs of a campaign's waves and confirmation do what the campaign says now. Every call is safe to repeat and
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

    /**
     * Brings the runs in line with the campaign as stored, holding its row meanwhile. A paused campaign holds back
     * every case that has not started, and cases already executing finish; a running one allows its parallelism; a
     * confirming one lets its current run play at its parallelism. Everyone who changes a campaign reconciles
     * afterwards and no write of the campaign can pass the lock, so the last reconciliation always read the newest
     * campaign: a step or a request that read an older one cannot leave its value on the runs.
     */
    void reconcile(UUID campaignId) {
        campaigns.whileLocked(campaignId, campaign -> {
            switch (campaign.state()) {
                case RUNNING -> allow(campaignId, campaign.parallelism());
                case PAUSED -> hold(campaignId);
                case CONFIRMING -> allowCurrentRun(campaign.confirmation(), campaign.parallelism());
                case CANCELLED -> stop(campaignId);
                default -> {
                    // nothing plays in any other state
                }
            }
        });
    }

    private void hold(UUID campaignId) {
        campaigns.waves(campaignId).forEach(wave -> runs.setConcurrency(wave.runId(), HELD));
    }

    private void allow(UUID campaignId, int parallelism) {
        for (var wave : campaigns.waves(campaignId)) {
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

    /** Cancels the runs, the confirmation runs of the campaign included. */
    void stop(UUID campaignId) {
        campaigns.waves(campaignId).forEach(wave -> runs.cancel(wave.runId()));
        campaigns
                .find(campaignId)
                .map(stored -> stored.campaign().confirmation())
                .ifPresent(confirmation -> confirmation.runs().forEach(run -> runs.cancel(run.runId())));
    }
}
