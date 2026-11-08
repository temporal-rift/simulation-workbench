package io.github.temporalrift.workbench.search.application.command;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.search.application.port.in.StartConfirmationUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.FidelityNotEstablishedException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException;
import io.github.temporalrift.workbench.search.domain.campaign.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ConfirmationSeeds;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.search.domain.port.out.FidelityGate;

/**
 * Confirms a finished search's short list on the simulator. The seeds derive from the confirmation master seed only.
 * Nothing is frozen unless they overlap no seed the campaign's log records, the simulator is enabled and its fidelity
 * established, and every policy the criterion names has a bundle. One experiment per short-listed candidate is then
 * frozen and one run per experiment is started held: the campaign write releases the first, and the worker releases
 * each next one when the one before it is complete, so the runs play one after another and nothing plays before the
 * campaign records the confirmation. Experiments and runs are requested with keys derived from the campaign and the
 * candidate, so a request that crashed or lost its write is repeated into the same ones.
 */
public class StartConfirmationCommandHandler implements StartConfirmationUseCase {

    private static final int ATTEMPTS = 5;

    private final CampaignRepository campaigns;
    private final CampaignExperiments experiments;
    private final CampaignRuns runs;
    private final CandidateEvaluation evaluation;
    private final FidelityGate gate;
    private final WaveRuns waveRuns;

    public StartConfirmationCommandHandler(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CampaignRuns runs,
            CandidateEvaluation evaluation,
            FidelityGate gate) {
        this.campaigns = campaigns;
        this.experiments = experiments;
        this.runs = runs;
        this.evaluation = evaluation;
        this.gate = gate;
        this.waveRuns = new WaveRuns(campaigns, runs);
    }

    @Override
    public Campaign handle(UUID campaignId) {
        var stored = campaigns.find(campaignId).orElseThrow(() -> new CampaignNotFoundException(campaignId));
        requireSearchCompleted(stored.campaign());
        var definition = stored.campaign().definition();
        var seedCount = evaluation.confirmationSeedCount(definition.criterionVersion());
        var seeds = new ConfirmationSeeds(definition.confirmationMasterSeed()).seeds(seedCount);
        rejectOverlap(campaignId, seeds);
        requirePlayable(definition);
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
                waveRuns.allowConfirmation(confirmation, next.parallelism());
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

    /** Whether the simulator can play the confirmation. The fidelity gate is also shut for a disabled simulator. */
    private void requirePlayable(CampaignDefinition definition) {
        if (!experiments.simulatorEnabled()) {
            throw new SimulatorNotAvailableException("The simulator is not enabled");
        }
        if (!gate.established()) {
            throw new FidelityNotEstablishedException();
        }
        experiments.bundles(evaluation.confirmationPolicies(definition.criterionVersion()));
    }

    /** One experiment and one held run per short-listed candidate, in short-list order. */
    private Confirmation freezeAndStart(Campaign campaign, int seedCount, List<String> seeds, List<SeatTable> tables) {
        var byId = campaigns.candidates(campaign.campaignId()).stream()
                .collect(Collectors.toMap(Candidate::id, candidate -> candidate));
        var started = new ArrayList<Confirmation.Run>();
        for (var id : campaign.shortList()) {
            var experimentId = experiments.freezeConfirmation(
                    ConfirmationKeys.experiment(campaign.campaignId(), id),
                    campaign.definition(),
                    byId.get(id),
                    seeds,
                    tables);
            var runId = runs.startHeld(experimentId, ConfirmationKeys.run(campaign.campaignId(), id, experimentId));
            started.add(new Confirmation.Run(id, experimentId, runId, null));
        }
        return new Confirmation(seedCount, started);
    }
}
