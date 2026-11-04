package io.github.temporalrift.workbench.search.domain.port.out;

import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ParameterGrid;

/** Driven port to the experiment module: the space a search moves in and the experiments that play its waves. */
public interface CampaignExperiments {

    /**
     * The parameters the definition's baseline lets a candidate override.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException when the baseline
     *     or the parameter space is unknown
     */
    ParameterGrid grid(CampaignDefinition definition);

    /** Materialises overrides on the definition's baseline exactly as an experiment variant is. */
    CandidateValidation validation(CampaignDefinition definition);

    /**
     * Freezes the experiment of one wave, once per idempotency key: a variant per candidate, labelled by its id, each
     * playing every seed over the full matrix of the definition's policies and player counts.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException when the
     *     experiment is refused
     */
    UUID freeze(
            UUID idempotencyKey,
            CampaignDefinition definition,
            int waveIndex,
            List<Candidate> candidates,
            List<String> seeds);

    /** How many cases a candidate playing one seed has, which sizes a wave. */
    int casesPerGame(CampaignDefinition definition);
}
