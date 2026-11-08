package io.github.temporalrift.workbench.search.application.command;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.search.application.port.in.StartConfirmationUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException;
import io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ConfirmationSeeds;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

/**
 * Confirms a finished search's short list. The seeds derive from the confirmation master seed only and are checked
 * against every seed the campaign's log records before anything is frozen. One experiment per subject, the baseline
 * and each short-listed candidate, is frozen on the real lane and one run per experiment is started held: the
 * campaign write releases the first, and the worker releases each next one when the one before it is complete, so only
 * one confirmation case executes at a time and nothing plays before the campaign records the confirmation.
 * Experiments and runs are requested with keys derived from the campaign and the subject, so a request that crashed
 * or lost its write is repeated into the same ones.
 */
public class StartConfirmationCommandHandler implements StartConfirmationUseCase {

    private static final int ATTEMPTS = 5;

    private final CampaignRepository campaigns;
    private final CampaignExperiments experiments;
    private final CampaignRuns runs;
    private final CandidateEvaluation evaluation;

    public StartConfirmationCommandHandler(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CampaignRuns runs,
            CandidateEvaluation evaluation) {
        this.campaigns = campaigns;
        this.experiments = experiments;
        this.runs = runs;
        this.evaluation = evaluation;
    }

    @Override
    public Campaign handle(UUID campaignId) {
        var stored = campaigns.find(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
        requireSearchCompleted(stored.campaign());
        var definition = stored.campaign().definition();
        var seedCount = evaluation.confirmationSeedCount(definition.criterionVersion());
        var seeds = new ConfirmationSeeds(definition.confirmationMasterSeed()).seeds(seedCount);
        rejectOverlap(campaignId, seeds);
        var tables = evaluation.confirmationTables(definition.criterionVersion());
        var confirmation = freezeAndStart(stored.campaign(), seedCount, seeds, tables);
        for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
            var current = campaigns.find(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
            if (current.campaign().state() != CampaignState.SEARCH_COMPLETED) {
                // A confirming campaign owns these runs through another request; a campaign that ended while they
                // were being set up must not leave them waiting.
                if (current.campaign().state() != CampaignState.CONFIRMING) {
                    cancel(confirmation);
                }
                throw new InvalidCampaignStateException(
                        "startConfirmation", current.campaign().state());
            }
            var next = current.campaign().startConfirmation(confirmation);
            if (campaigns.apply(CampaignChange.of(current, next, current.accruedAt()))) {
                runs.setConcurrency(confirmation.runs().getFirst().runId(), 1);
                return next;
            }
        }
        throw new IllegalStateException("Campaign " + campaignId + " keeps changing under the request");
    }

    /** Runs that wait for their turn are held, so a setup that fails or loses leaves nothing playing. */
    private void cancel(Confirmation confirmation) {
        confirmation.runs().forEach(run -> {
            try {
                runs.cancel(run.runId());
            } catch (RuntimeException _) {
                // every other run is still cancelled; a run that stays held plays nothing
            }
        });
    }

    private static void requireSearchCompleted(Campaign campaign) {
        if (campaign.state() != CampaignState.SEARCH_COMPLETED) {
            throw new InvalidCampaignStateException("startConfirmation", campaign.state());
        }
    }

    private void rejectOverlap(UUID campaignId, List<String> seeds) {
        var shared = campaigns.loggedAmong(campaignId, seeds).stream().sorted().toList();
        if (!shared.isEmpty()) {
            throw new SeedOverlapException(shared);
        }
    }

    private Confirmation freezeAndStart(Campaign campaign, int seedCount, List<String> seeds, List<SeatTable> tables) {
        var definition = campaign.definition();
        var subjects = subjects(campaign);
        var started = new ArrayList<Confirmation.Run>();
        for (var subject : subjects) {
            var experimentId = experiments.freezeConfirmation(
                    ConfirmationKeys.experiment(campaign.campaignId(), subject.label()),
                    definition,
                    subject,
                    seeds,
                    tables);
            var runKey = ConfirmationKeys.run(campaign.campaignId(), subject.label(), experimentId);
            started.add(new Confirmation.Run(subject.label(), experimentId, startRun(experimentId, runKey), null));
        }
        return new Confirmation(seedCount, started);
    }

    /** A run no lane fits is a confirmation no real lane can play. */
    private UUID startRun(UUID experimentId, UUID key) {
        try {
            return runs.startHeld(experimentId, key);
        } catch (CampaignPlayRefusedException e) {
            throw new NoRealLaneException(e.getMessage(), e);
        }
    }

    /** The baseline first, then the short-listed candidates in short-list order. */
    private List<CampaignExperiments.Subject> subjects(Campaign campaign) {
        var baseline = experiments
                .validation(campaign.definition())
                .validate(Map.<String, BigDecimal>of())
                .orElseThrow(() -> new CampaignPlayRefusedException("The baseline rules cannot be materialised"));
        var byId = campaigns.candidates(campaign.campaignId()).stream()
                .collect(Collectors.toMap(Candidate::id, candidate -> candidate));
        var subjects = new ArrayList<CampaignExperiments.Subject>();
        subjects.add(new CampaignExperiments.Subject(
                Confirmation.BASELINE, baseline.overrides(), baseline.effectiveRulesDigest()));
        for (var id : campaign.shortList()) {
            var candidate = byId.get(id);
            subjects.add(new CampaignExperiments.Subject(id, candidate.overrides(), candidate.effectiveRulesDigest()));
        }
        return subjects;
    }
}
