package io.github.temporalrift.workbench.search.domain.port.out;

import java.math.BigDecimal;
import java.util.List;
import java.util.SortedMap;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

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

    /**
     * Checks that the definition names a served parameter space and a baseline that some lane publishes.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException when it does not
     */
    void check(CampaignDefinition definition);

    /** Materialises overrides on the definition's baseline exactly as an experiment variant is. */
    CandidateValidation validation(CampaignDefinition definition);

    /**
     * The policy bundles that serve the policies, each the newest bundle of its major version.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException when a policy has no
     *     bundle
     */
    List<JsonNode> bundles(List<io.github.temporalrift.workbench.search.domain.campaign.PolicyRef> policies);

    /**
     * Freezes the experiment of one wave, once per idempotency key: a variant per candidate, labelled by its id, each
     * playing every seed over the full matrix of the given tables and the definition's player counts.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException when the
     *     experiment is refused
     */
    UUID freeze(
            UUID idempotencyKey,
            CampaignDefinition definition,
            int waveIndex,
            List<Candidate> candidates,
            List<String> seeds,
            List<io.github.temporalrift.workbench.search.domain.campaign.SeatTable> tables);

    /**
     * Freezes the experiment that confirms one subject on the deployed lane that is build-equivalent to the
     * simulator, once per idempotency key: a single variant labelled by the subject, playing every seed over the
     * full matrix of the given policies and the definition's player counts, one case at a time. The key is combined
     * with the builds of the lane, so a lane redeployed since an earlier attempt gets an experiment of its own.
     *
     * @throws io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException when no such lane exists
     * @throws io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException when the
     *     experiment is refused
     */
    UUID freezeConfirmation(
            UUID idempotencyKey,
            CampaignDefinition definition,
            Subject subject,
            List<String> seeds,
            List<io.github.temporalrift.workbench.search.domain.campaign.PolicyRef> policies);

    /**
     * What a confirmation experiment plays.
     *
     * @param label the baseline label or a candidate id
     * @param overrides what differs from the baseline; empty for the baseline itself
     */
    record Subject(String label, SortedMap<String, BigDecimal> overrides, String effectiveRulesDigest) {}

    /** How many cases a candidate playing one seed over the tables has, which sizes a wave. */
    int casesPerGame(
            CampaignDefinition definition,
            List<io.github.temporalrift.workbench.search.domain.campaign.SeatTable> tables);
}
