package io.github.temporalrift.workbench.search.application.command;

import java.time.Clock;
import java.util.HashMap;

import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.StoredCampaign;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

/**
 * One step of a confirming campaign. The confirmation runs play one after another, each at the campaign's parallelism:
 * the first run that is not complete may play and the rest wait. A failed or cancelled run fails the campaign. When
 * every run is complete, each is evaluated in confirmation mode and the campaign completes with all the evaluations
 * in one write.
 */
class ConfirmationProgress {

    private final CampaignRuns runs;
    private final CandidateEvaluation evaluation;
    private final WaveRuns waveRuns;
    private final CampaignRepository campaigns;
    private final Clock clock;

    ConfirmationProgress(
            CampaignRepository campaigns,
            CampaignRuns runs,
            CandidateEvaluation evaluation,
            WaveRuns waveRuns,
            Clock clock) {
        this.campaigns = campaigns;
        this.runs = runs;
        this.evaluation = evaluation;
        this.waveRuns = waveRuns;
        this.clock = clock;
    }

    /** Whether the step changed the campaign. */
    boolean step(StoredCampaign stored) {
        var campaign = stored.campaign();
        var confirmation = campaign.confirmation();
        var unfinished = false;
        for (var run : confirmation.runs()) {
            var progress = runs.progress(run.runId());
            if (progress.isEmpty()) {
                return fail(stored, "Confirmation run " + run.runId() + " of " + run.subject() + " is gone");
            }
            switch (progress.get().phase()) {
                case FAILED -> {
                    return fail(
                            stored,
                            "Confirmation run of " + run.subject() + " failed: "
                                    + progress.get().failureMessage());
                }
                case CANCELLED -> {
                    return fail(stored, "Confirmation run of " + run.subject() + " was cancelled");
                }
                case COMPLETED -> {
                    // played to the end
                }
                case ACTIVE, INTERRUPTED -> unfinished = true;
            }
        }
        if (unfinished) {
            waveRuns.reconcile(campaign.campaignId());
            return false;
        }
        return complete(stored);
    }

    private boolean complete(StoredCampaign stored) {
        var campaign = stored.campaign();
        var evaluations = new HashMap<String, Confirmation.Evaluation>();
        for (var run : campaign.confirmation().runs()) {
            try {
                var evaluated =
                        evaluation.confirm(campaign.definition().criterionVersion(), run.runId(), run.subject());
                evaluations.put(run.subject(), new Confirmation.Evaluation(evaluated.verdict(), evaluated.score()));
            } catch (IllegalArgumentException e) {
                var next = campaign.fail(
                        new CampaignFailure(CampaignFailure.CONFIRMATION_EVALUATION_FAILED, e.getMessage()),
                        clock.instant());
                return campaigns.apply(CampaignChange.of(stored, next, stored.accruedAt()));
            }
        }
        var next = campaign.completeConfirmation(campaign.confirmation().evaluatedAs(evaluations), clock.instant());
        return campaigns.apply(CampaignChange.of(stored, next, stored.accruedAt()));
    }

    private boolean fail(StoredCampaign stored, String message) {
        var campaign = stored.campaign();
        // The runs stop first: a failure that stopped halfway is seen again, because the campaign is still
        // confirming and every run it names is already over.
        waveRuns.stop(campaign.campaignId());
        var next =
                campaign.fail(new CampaignFailure(CampaignFailure.CONFIRMATION_RUN_FAILED, message), clock.instant());
        return campaigns.apply(CampaignChange.of(stored, next, stored.accruedAt()));
    }
}
