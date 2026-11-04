package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ParameterGrid;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateValidation;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.FactionSets;

/**
 * Prepares and freezes the experiments of a campaign through the experiment module. A wave is a frozen experiment
 * whose variants are candidates labelled by their ids, on the simulator and over the faction sets of every player
 * count of the campaign under cyclic seat rotation, so every candidate plays every seed over the full matrix.
 */
public class CampaignExperimentsAdapter implements CampaignExperiments {

    private static final int SCHEMA_VERSION = 1;

    /** A wave plays one case at a time until the campaign allows more, so the frozen bounds never depend on it. */
    private static final int FROZEN_CONCURRENCY = 1;

    private final ExperimentFactory experiments;
    private final LaneEnvironments lanes;
    private final ObjectMapper mapper;
    private final Bounds bounds;

    public CampaignExperimentsAdapter(
            ExperimentFactory experiments, LaneEnvironments lanes, ObjectMapper mapper, Bounds bounds) {
        this.experiments = experiments;
        this.lanes = lanes;
        this.mapper = mapper;
        this.bounds = bounds;
    }

    /**
     * @param caseWallTimeoutSeconds how long one case of a wave may take
     * @param maxRejectedCandidatesPerWindow how many refused candidates a decision window of a case may spend
     */
    public record Bounds(int caseWallTimeoutSeconds, int maxRejectedCandidatesPerWindow) {}

    @Override
    public ParameterGrid grid(CampaignDefinition definition) {
        try {
            return new ParameterGrid(
                    experiments
                            .parameterAxes(definition.baselineRulesDigest(), definition.parameterSpaceVersion())
                            .stream()
                            .map(axis -> new ParameterGrid.Axis(
                                    axis.key(), axis.min(), axis.max(), axis.step(), axis.baseline()))
                            .toList());
        } catch (ExperimentRefusedException | IllegalArgumentException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public CandidateValidation validation(CampaignDefinition definition) {
        return overrides -> {
            try {
                var preview = experiments.previewVariant(
                        definition.baselineRulesDigest(), definition.parameterSpaceVersion(), overrides);
                return Optional.of(
                        new CandidateValidation.Validated(preview.effectiveRulesDigest(), preview.overrides()));
            } catch (ExperimentRefusedException _) {
                return Optional.empty();
            }
        };
    }

    @Override
    public UUID freeze(
            UUID idempotencyKey,
            CampaignDefinition definition,
            int waveIndex,
            List<Candidate> candidates,
            List<String> seeds) {
        var simulator =
                lanes.simulator().orElseThrow(() -> new CampaignPlayRefusedException("The simulator is not enabled"));
        try {
            return experiments.freeze(idempotencyKey, manifest(definition, waveIndex, candidates, seeds, simulator));
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public int casesPerGame(CampaignDefinition definition) {
        var seats = FactionSets.of(definition.playerCounts()).stream()
                .mapToInt(List::size)
                .sum();
        return Math.max(1, seats * definition.policies().size());
    }

    private ObjectNode manifest(
            CampaignDefinition definition,
            int waveIndex,
            List<Candidate> candidates,
            List<String> seeds,
            LaneBundle simulator) {
        var manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("name", definition.name() + ", wave " + waveIndex);
        var variants = manifest.putArray("variants");
        candidates.forEach(candidate -> variants.add(variant(definition, candidate, simulator)));
        var services = manifest.putObject("services");
        services.set("gameService", service(simulator.gameService()));
        services.set("timelineService", service(simulator.timelineService()));
        services.set("readService", service(simulator.readService()));
        var contracts = manifest.putObject("contracts");
        simulator.contracts().forEach(contracts::put);
        manifest.set("policies", definition.policies().deepCopy());
        var seedNodes = manifest.putArray("seeds");
        seeds.forEach(seedNodes::add);
        var counts = manifest.putArray("playerCounts");
        definition.playerCounts().forEach(counts::add);
        var sets = manifest.putArray("factionSets");
        FactionSets.of(definition.playerCounts()).forEach(set -> {
            var node = sets.addArray();
            set.stream().map(Faction::name).forEach(node::add);
        });
        manifest.put("seatRotationMode", "CYCLIC");
        manifest.put("timingMode", "LOGICAL");
        manifest.put("concurrency", FROZEN_CONCURRENCY);
        manifest.put("caseWallTimeoutSeconds", bounds.caseWallTimeoutSeconds());
        manifest.put("maxRejectedCandidatesPerWindow", bounds.maxRejectedCandidatesPerWindow());
        return manifest;
    }

    private ObjectNode variant(CampaignDefinition definition, Candidate candidate, LaneBundle simulator) {
        var node = mapper.createObjectNode();
        node.put("label", candidate.id());
        node.set("rulesArtifact", definition.baseline().path("rulesArtifact").deepCopy());
        node.set(
                "contentArtifact", definition.baseline().path("contentArtifact").deepCopy());
        node.put("effectiveRulesDigest", candidate.effectiveRulesDigest());
        node.put("effectiveContentDigest", simulator.effectiveContentDigest());
        if (!candidate.overrides().isEmpty()) {
            var overrides = node.putObject("overrides");
            candidate.overrides().forEach(overrides::put);
            node.put("parameterSpaceVersion", definition.parameterSpaceVersion());
        }
        return node;
    }

    private ObjectNode service(LaneBundle.ServiceBuild build) {
        var node = mapper.createObjectNode();
        node.put("imageDigest", build.imageDigest());
        node.put("sourceRevision", build.sourceRevision());
        return node;
    }
}
