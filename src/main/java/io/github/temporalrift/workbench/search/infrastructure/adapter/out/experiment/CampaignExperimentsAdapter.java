package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.NoRealLaneException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
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

    /** The seats of the largest case of the matrix. */
    private static final int MAX_SEATS = 5;

    /** A wave plays one case at a time until the campaign allows more, so the frozen bounds never depend on it. */
    private static final int FROZEN_CONCURRENCY = 1;

    private final ExperimentFactory experiments;
    private final PolicyCatalog catalog;
    private final LaneEnvironments lanes;
    private final ObjectMapper mapper;
    private final Bounds bounds;

    public CampaignExperimentsAdapter(
            ExperimentFactory experiments,
            PolicyCatalog catalog,
            LaneEnvironments lanes,
            ObjectMapper mapper,
            Bounds bounds) {
        this.experiments = experiments;
        this.catalog = catalog;
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
    public void check(CampaignDefinition definition) {
        try {
            experiments.parameterAxes(definition.baselineRulesDigest(), definition.parameterSpaceVersion());
        } catch (ExperimentRefusedException e) {
            var unknownSpace =
                    InvalidCampaignReason.UNKNOWN_PARAMETER_SPACE.name().equals(e.reason());
            throw new InvalidCampaignException(
                    unknownSpace
                            ? InvalidCampaignReason.UNKNOWN_PARAMETER_SPACE
                            : InvalidCampaignReason.UNKNOWN_BASELINE,
                    unknownSpace ? "parameterSpaceVersion" : "baseline.rulesArtifact.digest",
                    e.getMessage());
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
        var name = definition.name() + ", wave " + waveIndex;
        var variants = candidates.stream()
                .map(candidate -> variant(
                        definition, candidate.id(), candidate.overrides(), candidate.effectiveRulesDigest(), simulator))
                .toList();
        try {
            return experiments.freeze(
                    idempotencyKey,
                    manifest(definition, name, definitionPolicies(definition), variants, seeds, simulator));
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public UUID freezeConfirmation(
            UUID idempotencyKey,
            CampaignDefinition definition,
            Subject subject,
            List<String> seeds,
            List<PolicyRef> required) {
        var seats = definition.playerCounts().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1);
        var lane = lanes.equivalentDeployedLane(seats)
                .orElseThrow(() -> new NoRealLaneException(
                        "No deployed lane is build-equivalent to the simulator for " + seats + " seats"));
        var manifest = manifest(
                definition,
                definition.name() + ", confirmation of " + subject.label(),
                policies(required),
                List.of(variant(
                        definition,
                        subject.label(),
                        subject.overrides(),
                        subject.effectiveRulesDigest(),
                        lane.bundle())),
                seeds,
                lane.bundle());
        try {
            return experiments.freeze(builtOn(idempotencyKey, lane.bundle()), manifest);
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    /** The key of the experiment on these builds: a lane redeployed since an earlier attempt starts a new one. */
    private static UUID builtOn(UUID idempotencyKey, LaneBundle bundle) {
        var builds = bundle.gameService().imageDigest()
                + bundle.timelineService().imageDigest()
                + bundle.readService().imageDigest();
        return UUID.nameUUIDFromBytes(
                (idempotencyKey + ":" + builds).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The bundles of the policies the criterion names, every seat of the matrix playing the same one. */
    private List<JsonNode> policies(List<PolicyRef> required) {
        var nodes = new java.util.ArrayList<JsonNode>();
        for (var wanted : required) {
            var bundle = catalog.list().stream()
                    .filter(candidate -> candidate.id().equals(wanted.id())
                            && candidate.version().startsWith(wanted.majorVersion() + "."))
                    .findFirst()
                    .orElseThrow(() -> new CampaignPlayRefusedException(
                            "No policy bundle serves " + wanted.id() + " version " + wanted.majorVersion()));
            var node = mapper.createObjectNode();
            node.put("id", bundle.id());
            node.put("version", bundle.version());
            node.put("artifactDigest", bundle.artifactDigest());
            node.putObject("parameters");
            var seats = node.putArray("seatAssignment");
            for (var seat = 0; seat < MAX_SEATS; seat++) {
                seats.addObject()
                        .put("seatIndex", seat)
                        .put("policyId", bundle.id())
                        .put("policyVersion", bundle.version());
            }
            nodes.add(node);
        }
        return nodes;
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
            String name,
            List<? extends JsonNode> policies,
            List<ObjectNode> variants,
            List<String> seeds,
            LaneBundle simulator) {
        var manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("name", name);
        manifest.putArray("variants").addAll(variants);
        var services = manifest.putObject("services");
        services.set("gameService", service(simulator.gameService()));
        services.set("timelineService", service(simulator.timelineService()));
        services.set("readService", service(simulator.readService()));
        var contracts = manifest.putObject("contracts");
        simulator.contracts().forEach(contracts::put);
        manifest.putArray("policies").addAll(policies);
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

    private static List<JsonNode> definitionPolicies(CampaignDefinition definition) {
        var nodes = new java.util.ArrayList<JsonNode>();
        definition.policies().forEach(policy -> nodes.add(policy.deepCopy()));
        return nodes;
    }

    private ObjectNode variant(
            CampaignDefinition definition,
            String label,
            Map<String, BigDecimal> overrides,
            String effectiveRulesDigest,
            LaneBundle simulator) {
        var node = mapper.createObjectNode();
        node.put("label", label);
        node.set("rulesArtifact", definition.baseline().path("rulesArtifact").deepCopy());
        node.set(
                "contentArtifact", definition.baseline().path("contentArtifact").deepCopy());
        node.put("effectiveRulesDigest", effectiveRulesDigest);
        node.put("effectiveContentDigest", simulator.effectiveContentDigest());
        if (!overrides.isEmpty()) {
            var overrideNodes = node.putObject("overrides");
            overrides.forEach(overrideNodes::put);
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
