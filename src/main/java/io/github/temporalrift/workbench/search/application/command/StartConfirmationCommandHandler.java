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
 * and each short-listed candidate, is frozen on the real lane and one run per experiment is started: the first plays,
 * the others wait their turn so only one confirmation case executes at a time. Experiments and runs are requested
 * with keys derived from the campaign and the subject, so a request that crashed or lost its write is repeated into
 * the same ones.
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
        var confirmation = freezeAndStart(stored.campaign(), seedCount, seeds);
        for (var attempt = 0; attempt < ATTEMPTS; attempt++) {
            var current = campaigns.find(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
            if (current.campaign().state() != CampaignState.SEARCH_COMPLETED) {
                // Another request or a designer moved the campaign; the runs keyed to it belong to that request.
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

    private static void requireSearchCompleted(Campaign campaign) {
        if (campaign.state() != CampaignState.SEARCH_COMPLETED) {
            throw new InvalidCampaignStateException("startConfirmation", campaign.state());
        }
    }

    private void rejectOverlap(UUID campaignId, List<String> seeds) {
        var logged = campaigns.loggedSeeds(campaignId);
        var shared = seeds.stream().filter(logged::contains).sorted().toList();
        if (!shared.isEmpty()) {
            throw new SeedOverlapException(shared);
        }
    }

    private Confirmation freezeAndStart(Campaign campaign, int seedCount, List<String> seeds) {
        var definition = campaign.definition();
        var subjects = subjects(campaign);
        var started = new ArrayList<Confirmation.Run>();
        try {
            for (var subject : subjects) {
                var experimentId = experiments.freezeConfirmation(
                        ConfirmationKeys.experiment(campaign.campaignId(), subject.label()),
                        definition,
                        subject,
                        seeds);
                var runKey = ConfirmationKeys.run(campaign.campaignId(), subject.label());
                var runId = startRun(started.isEmpty(), experimentId, runKey);
                started.add(new Confirmation.Run(subject.label(), experimentId, runId, null));
            }
        } catch (RuntimeException e) {
            started.forEach(run -> runs.cancel(run.runId()));
            throw e;
        }
        return new Confirmation(seedCount, started);
    }

    /** A run no lane fits is a confirmation no real lane can play. */
    private UUID startRun(boolean first, UUID experimentId, UUID key) {
        try {
            return first ? runs.start(experimentId, key) : runs.startHeld(experimentId, key);
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
