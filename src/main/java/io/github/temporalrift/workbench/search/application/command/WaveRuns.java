package io.github.temporalrift.workbench.search.application.command;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.wave.Wave;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

/**
 * Applies what a campaign says to the runs that play its waves. Pausing holds back every case that has not started,
 * and cases already executing finish; resuming and a change of parallelism allow that many cases at once; stopping
 * cancels the runs. Every call is safe to repeat and ignores a run that is over.
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

    void stop(UUID campaignId) {
        campaigns.waves(campaignId).forEach(wave -> runs.cancel(wave.runId()));
    }
}
