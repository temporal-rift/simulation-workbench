package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import java.util.Arrays;
import java.util.Comparator;
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
import io.github.temporalrift.workbench.policy.PolicyDescriptor;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyNotAvailableException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.campaign.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ParameterGrid;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateValidation;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.FactionSets;
import io.github.temporalrift.workbench.shared.rules.ParameterValue;

/**
 * Prepares and freezes the experiments of a campaign through the experiment module. A wave is a frozen experiment
 * whose variants are candidates labelled by their ids, on the simulator and over the faction sets of every player
 * count of the campaign under cyclic seat rotation, so every candidate plays every seed over the full matrix of
 * the criterion's screening tables.
 */
public class CampaignExperimentsAdapter implements CampaignExperiments {

    private static final String VERSION_FIELD = "version";
    private static final int SCHEMA_VERSION = 2;

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
                            .<ParameterGrid.Axis>map(CampaignExperimentsAdapter::axis)
                            .toList());
        } catch (ExperimentRefusedException | IllegalArgumentException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    private static ParameterGrid.Axis axis(ExperimentFactory.ParameterAxis axis) {
        return switch (axis) {
            case ExperimentFactory.NumberAxis number ->
                new ParameterGrid.NumberAxis(
                        number.key(), number.min(), number.max(), number.step(), number.baseline());
            case ExperimentFactory.BooleanAxis flag -> new ParameterGrid.FlagAxis(flag.key(), flag.baseline());
        };
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
            List<String> seeds,
            List<SeatTable> tables) {
        var simulator =
                lanes.simulator().orElseThrow(() -> new CampaignPlayRefusedException("The simulator is not enabled"));
        var name = definition.name() + ", wave " + waveIndex;
        var variants = candidates.stream()
                .map(candidate -> variant(
                        definition, candidate.id(), candidate.overrides(), candidate.effectiveRulesDigest(), simulator))
                .toList();
        try {
            var seatTables = tableNodes(definition, tables);
            return experiments.freeze(
                    idempotencyKey,
                    manifest(
                            definition, name, usedBundles(definition, tables), seatTables, variants, seeds, simulator));
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public UUID freezeConfirmation(
            UUID idempotencyKey,
            CampaignDefinition definition,
            Candidate candidate,
            List<String> seeds,
            List<SeatTable> tables) {
        var simulator =
                lanes.simulator().orElseThrow(() -> new SimulatorNotAvailableException("The simulator is not enabled"));
        var seats = definition.playerCounts().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1);
        var required = tables.stream()
                .flatMap(table -> table.seats().subList(0, seats).stream())
                .distinct()
                .toList();
        var bundles = bundles(required);
        var manifest = manifest(
                definition,
                definition.name() + ", confirmation of " + candidate.id(),
                bundles,
                confirmationTables(tables, required, bundles, seats),
                List.of(variant(
                        definition,
                        candidate.id(),
                        candidate.overrides(),
                        candidate.effectiveRulesDigest(),
                        simulator)),
                seeds,
                simulator);
        try {
            return experiments.freeze(builtOn(idempotencyKey, simulator), manifest);
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public boolean simulatorEnabled() {
        return lanes.simulator().isPresent();
    }

    /** The key of the experiment on these builds: a simulator rebuilt since an earlier attempt starts a new one. */
    private static UUID builtOn(UUID idempotencyKey, LaneBundle bundle) {
        var builds = bundle.gameService().imageDigest()
                + bundle.timelineService().imageDigest()
                + bundle.readService().imageDigest();
        return UUID.nameUUIDFromBytes(
                (idempotencyKey + ":" + builds).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    /** The newest bundle of each policy, as a manifest states a bundle. */
    @Override
    public List<JsonNode> bundles(List<PolicyRef> required) {
        var nodes = new java.util.ArrayList<JsonNode>();
        for (var wanted : required) {
            var bundle = catalog.list().stream()
                    .filter(candidate -> candidate.id().equals(wanted.id())
                            && candidate.version().startsWith(wanted.majorVersion() + "."))
                    .max(Comparator.comparing(PolicyDescriptor::version, CampaignExperimentsAdapter::compareVersions))
                    .orElseThrow(() -> new PolicyNotAvailableException(
                            "No policy bundle serves " + wanted.id() + " version " + wanted.majorVersion()));
            var node = mapper.createObjectNode();
            node.put("id", bundle.id());
            node.put(VERSION_FIELD, bundle.version());
            node.put("artifactDigest", bundle.artifactDigest());
            node.putObject("parameters");
            nodes.add(node);
        }
        return nodes;
    }

    @Override
    public int casesPerGame(CampaignDefinition definition, List<SeatTable> tables) {
        var seats = FactionSets.of(definition.playerCounts()).stream()
                .mapToInt(List::size)
                .sum();
        return Math.max(1, seats * tables.size());
    }

    /** The bundle of the definition that serves the policy: the newest one of its major version. */
    private static JsonNode bundleOf(CampaignDefinition definition, PolicyRef policy) {
        return definition
                .policies()
                .valueStream()
                .filter(bundle -> policy.id().equals(bundle.path("id").asString())
                        && bundle.path(VERSION_FIELD).asString().startsWith(policy.majorVersion() + "."))
                .max(Comparator.comparing(
                        (JsonNode bundle) -> bundle.path(VERSION_FIELD).asString(),
                        CampaignExperimentsAdapter::compareVersions))
                .orElseThrow(() -> new CampaignPlayRefusedException(
                        "The campaign has no policy bundle for " + policy.id() + " version " + policy.majorVersion()));
    }

    /** The bundles the tables play, once each, as a manifest states a bundle. */
    private static List<JsonNode> usedBundles(CampaignDefinition definition, List<SeatTable> tables) {
        var used = new java.util.LinkedHashMap<String, JsonNode>();
        for (var table : tables) {
            for (var policy : table.seats()) {
                var bundle = bundleOf(definition, policy);
                var copy = bundle.deepCopy();
                // A campaign stored before seat tables also carries a seat assignment, which was never played.
                ((ObjectNode) copy).remove("seatAssignment");
                used.putIfAbsent(
                        copy.path("id").asString() + "@"
                                + copy.path(VERSION_FIELD).asString(),
                        copy);
            }
        }
        return List.copyOf(used.values());
    }

    /** The tables as a manifest states them, each seat naming the bundle of the definition that serves its policy. */
    private List<JsonNode> tableNodes(CampaignDefinition definition, List<SeatTable> tables) {
        var seatCount = definition.playerCounts().stream()
                .mapToInt(Integer::intValue)
                .max()
                .orElse(1);
        var nodes = new java.util.ArrayList<JsonNode>();
        for (var table : tables) {
            var node = mapper.createObjectNode();
            node.put("id", table.id());
            var seats = node.putArray("seats");
            for (var seat = 0; seat < seatCount; seat++) {
                var bundle = bundleOf(definition, table.seats().get(seat));
                seats.addObject()
                        .put("seatIndex", seat)
                        .put("policyId", bundle.path("id").asString())
                        .put("policyVersion", bundle.path(VERSION_FIELD).asString());
            }
            nodes.add(node);
        }
        return nodes;
    }

    /** The criterion's confirmation tables as a manifest states them, each seat naming the bundle of its policy. */
    private List<JsonNode> confirmationTables(
            List<SeatTable> tables, List<PolicyRef> policies, List<JsonNode> bundles, int seatCount) {
        var nodes = new java.util.ArrayList<JsonNode>();
        for (var table : tables) {
            var node = mapper.createObjectNode();
            node.put("id", table.id());
            var seats = node.putArray("seats");
            for (var seat = 0; seat < seatCount; seat++) {
                var bundle = bundles.get(policies.indexOf(table.seats().get(seat)));
                seats.addObject()
                        .put("seatIndex", seat)
                        .put("policyId", bundle.path("id").asString())
                        .put("policyVersion", bundle.path(VERSION_FIELD).asString());
            }
            nodes.add(node);
        }
        return nodes;
    }

    private ObjectNode manifest(
            CampaignDefinition definition,
            String name,
            List<? extends JsonNode> policies,
            List<? extends JsonNode> tables,
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
        manifest.putArray("tables").addAll(tables);
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

    /** Orders dotted versions by their numbers, so 1.10.0 is newer than 1.2.0. */
    static int compareVersions(String left, String right) {
        var a = numbers(left);
        var b = numbers(right);
        var length = Math.max(a.length, b.length);
        return Arrays.compare(Arrays.copyOf(a, length), Arrays.copyOf(b, length));
    }

    /** The numbers of a dotted version, as in {@code [1, 10, 0]} for {@code 1.10.0}. */
    private static int[] numbers(String version) {
        return Arrays.stream(version.split("[.]")).mapToInt(Integer::parseInt).toArray();
    }

    private ObjectNode variant(
            CampaignDefinition definition,
            String label,
            Map<String, ParameterValue> overrides,
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
            overrides.forEach((key, value) -> overrideNodes.set(key, value.json()));
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
