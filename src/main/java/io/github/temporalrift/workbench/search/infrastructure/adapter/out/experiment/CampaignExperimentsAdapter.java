package io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyDescriptor;
import io.github.temporalrift.workbench.policy.PolicyParameterSpaceDescriptor;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignPlayRefusedException;
import io.github.temporalrift.workbench.search.domain.campaign.HuntPlan;
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
    private static final String SIMULATOR_DISABLED = "The simulator is not enabled";
    private static final String ARTIFACT_DIGEST_FIELD = "artifactDigest";
    private static final String SEATS_FIELD = "seats";
    private static final String SEAT_INDEX_FIELD = "seatIndex";
    private static final String POLICY_ID_FIELD = "policyId";
    private static final String POLICY_VERSION_FIELD = "policyVersion";
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
        if (definition.isHunt()) {
            return huntGrid(definition);
        }
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
            case ExperimentFactory.NumberAxis(var key, var min, var max, var step, var baseline) ->
                new ParameterGrid.NumberAxis(key, min, max, step, baseline);
            case ExperimentFactory.BooleanAxis(var key, var baseline) -> new ParameterGrid.FlagAxis(key, baseline);
        };
    }

    @Override
    public void check(CampaignDefinition definition) {
        if (definition.isHunt() && policySpace(definition).isEmpty()) {
            throw new InvalidCampaignException(
                    InvalidCampaignReason.UNKNOWN_PARAMETER_SPACE,
                    "policyParameterSpaceVersion",
                    "Policy parameter space " + definition.policyParameterSpaceVersion() + " is not served");
        }
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
        if (definition.isHunt()) {
            return huntValidation(definition);
        }
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
        if (definition.isHunt()) {
            return freezeHuntWave(idempotencyKey, definition, waveIndex, candidates, seeds);
        }
        var simulator = lanes.simulator().orElseThrow(() -> new CampaignPlayRefusedException(SIMULATOR_DISABLED));
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
        var simulator = lanes.simulator().orElseThrow(() -> new SimulatorNotAvailableException(SIMULATOR_DISABLED));
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
        return UUID.nameUUIDFromBytes((idempotencyKey + ":" + builds).getBytes(StandardCharsets.UTF_8));
    }

    /** The newest bundle of each policy, as a manifest states a bundle. */
    @Override
    public List<JsonNode> bundles(List<PolicyRef> required) {
        var nodes = new ArrayList<JsonNode>();
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
            node.put(ARTIFACT_DIGEST_FIELD, bundle.artifactDigest());
            node.putObject("parameters");
            nodes.add(node);
        }
        return nodes;
    }

    @Override
    public int casesPerGame(CampaignDefinition definition, List<SeatTable> tables) {
        if (definition.isHunt()) {
            return HuntPlan.casesPerSeed(definition.huntPlayerCount());
        }
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
        var used = new LinkedHashMap<String, JsonNode>();
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
        var nodes = new ArrayList<JsonNode>();
        for (var table : tables) {
            var node = mapper.createObjectNode();
            node.put("id", table.id());
            var seats = node.putArray(SEATS_FIELD);
            for (var seat = 0; seat < seatCount; seat++) {
                var bundle = bundleOf(definition, table.seats().get(seat));
                seats.addObject()
                        .put(SEAT_INDEX_FIELD, seat)
                        .put(POLICY_ID_FIELD, bundle.path("id").asString())
                        .put(POLICY_VERSION_FIELD, bundle.path(VERSION_FIELD).asString());
            }
            nodes.add(node);
        }
        return nodes;
    }

    /** The criterion's confirmation tables as a manifest states them, each seat naming the bundle of its policy. */
    private List<JsonNode> confirmationTables(
            List<SeatTable> tables, List<PolicyRef> policies, List<JsonNode> bundles, int seatCount) {
        var nodes = new ArrayList<JsonNode>();
        for (var table : tables) {
            var node = mapper.createObjectNode();
            node.put("id", table.id());
            var seats = node.putArray(SEATS_FIELD);
            for (var seat = 0; seat < seatCount; seat++) {
                var bundle = bundles.get(policies.indexOf(table.seats().get(seat)));
                seats.addObject()
                        .put(SEAT_INDEX_FIELD, seat)
                        .put(POLICY_ID_FIELD, bundle.path("id").asString())
                        .put(POLICY_VERSION_FIELD, bundle.path(VERSION_FIELD).asString());
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
        setFactionSets(manifest, FactionSets.of(definition.playerCounts()));
        manifest.put("seatRotationMode", "CYCLIC");
        manifest.put("timingMode", "LOGICAL");
        manifest.put("concurrency", FROZEN_CONCURRENCY);
        manifest.put("caseWallTimeoutSeconds", bounds.caseWallTimeoutSeconds());
        manifest.put("maxRejectedCandidatesPerWindow", bounds.maxRejectedCandidatesPerWindow());
        return manifest;
    }

    /** Names the faction sets the case matrix plays, in place of any it had. */
    private void setFactionSets(ObjectNode manifest, List<List<Faction>> factionSets) {
        var sets = manifest.putArray("factionSets");
        factionSets.forEach(set -> {
            var node = sets.addArray();
            set.stream().map(Faction::name).forEach(node::add);
        });
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

    // ---- policy hunt ----------------------------------------------------------------------------------------

    private static final String SEARCH_ID = "faction-search";
    private static final String SEARCH_VERSION = "1.0.0";
    private static final PolicyRef STANDARD = new PolicyRef("faction-spread", "1");

    private Optional<PolicySpace> policySpace(CampaignDefinition definition) {
        return catalog.parameterSpaces().stream()
                .filter(space -> space.version() == definition.policyParameterSpaceVersion())
                .findFirst()
                .map(space -> new PolicySpace(
                        space.parameters().stream()
                                .collect(Collectors.toMap(
                                        PolicyParameterSpaceDescriptor.Parameter::key, parameter -> parameter)),
                        space.searchedSubsets()
                                .getOrDefault(definition.faction().name(), List.of())));
    }

    /** The parameters of a served policy parameter space, and the keys a hunt of the faction searches. */
    private record PolicySpace(
            Map<String, PolicyParameterSpaceDescriptor.Parameter> parameters, List<String> searched) {}

    /** The axes of a hunt: the hunted faction's searched parameters, in key order, each at its default. */
    private ParameterGrid huntGrid(CampaignDefinition definition) {
        var space = policySpace(definition)
                .orElseThrow(() -> new CampaignPlayRefusedException("The policy parameter space is not served"));
        return new ParameterGrid(space.searched().stream()
                .sorted()
                .<ParameterGrid.Axis>map(key -> {
                    var parameter = space.parameters().get(key);
                    return new ParameterGrid.NumberAxis(
                            key,
                            BigDecimal.valueOf(parameter.min()),
                            BigDecimal.valueOf(parameter.max()),
                            BigDecimal.valueOf(parameter.step()),
                            BigDecimal.valueOf(parameter.defaultValue()));
                })
                .toList());
    }

    /**
     * A candidate of a hunt is one parameter set; what it "makes" is the artifact digest of the policy entry those
     * parameters name, so two draws of one parameter set are one candidate.
     */
    private CandidateValidation huntValidation(CampaignDefinition definition) {
        var space = policySpace(definition)
                .orElseThrow(() -> new CampaignPlayRefusedException("The policy parameter space is not served"));
        return overrides -> {
            var parameters = new LinkedHashMap<String, Object>();
            var kept = new TreeMap<String, ParameterValue>();
            for (var entry : overrides.entrySet()) {
                var parameter = space.parameters().get(entry.getKey());
                var json = entry.getValue().json();
                // A parameter of the space with a whole number, as given: nothing is truncated or dropped silently.
                if (parameter == null || !json.isIntegralNumber() || !json.canConvertToLong()) {
                    return Optional.empty();
                }
                var number = json.asLong();
                if (number != parameter.defaultValue()) {
                    parameters.put(entry.getKey(), number);
                    kept.put(entry.getKey(), entry.getValue());
                }
            }
            return catalog.artifactDigest(SEARCH_ID, SEARCH_VERSION, parameters)
                    .map(digest -> new CandidateValidation.Validated(digest, kept));
        };
    }

    /** The factions sets of the hunt's player count that hold the hunted faction. */
    private static List<List<Faction>> huntFactionSets(CampaignDefinition definition) {
        return FactionSets.of(definition.playerCounts()).stream()
                .filter(set -> set.contains(definition.faction()))
                .toList();
    }

    /** The policy entry of a hunt candidate, as a manifest states it. */
    private ObjectNode searchEntry(Candidate candidate) {
        var node = mapper.createObjectNode();
        node.put("id", SEARCH_ID);
        node.put(VERSION_FIELD, SEARCH_VERSION);
        node.put(ARTIFACT_DIGEST_FIELD, candidate.effectiveRulesDigest());
        var parameters = node.putObject("parameters");
        candidate
                .overrides()
                .forEach((key, value) -> parameters.put(key, value.json().asInt()));
        return node;
    }

    /** A table whose every seat plays the entry: only the hunted faction's seat reads the entry's parameters. */
    private ObjectNode uniformTable(CampaignDefinition definition, String id, JsonNode entry) {
        var seatCount = definition.huntPlayerCount();
        var node = mapper.createObjectNode();
        node.put("id", id);
        var seats = node.putArray(SEATS_FIELD);
        for (var seat = 0; seat < seatCount; seat++) {
            seats.addObject()
                    .put(SEAT_INDEX_FIELD, seat)
                    .put(POLICY_ID_FIELD, entry.path("id").asString())
                    .put(POLICY_VERSION_FIELD, entry.path(VERSION_FIELD).asString())
                    .put("policyDigest", entry.path(ARTIFACT_DIGEST_FIELD).asString());
        }
        return node;
    }

    /** A hunt's manifest: the baseline rules as one variant, over the faction sets that hold the hunted faction. */
    private ObjectNode huntManifest(
            CampaignDefinition definition,
            String name,
            List<? extends JsonNode> policies,
            List<? extends JsonNode> tables,
            List<String> seeds,
            LaneBundle simulator) {
        var manifest = manifest(
                definition, name, policies, tables, List.of(huntVariant(definition, simulator)), seeds, simulator);
        setFactionSets(manifest, huntFactionSets(definition));
        return manifest;
    }

    private ObjectNode huntVariant(CampaignDefinition definition, LaneBundle simulator) {
        try {
            var preview = experiments.previewVariant(
                    definition.baselineRulesDigest(), definition.parameterSpaceVersion(), Map.of());
            return variant(definition, "rules", Map.of(), preview.effectiveRulesDigest(), simulator);
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    /** A wave of a hunt: one table per candidate, over the rules of the baseline. */
    private UUID freezeHuntWave(
            UUID idempotencyKey,
            CampaignDefinition definition,
            int waveIndex,
            List<Candidate> candidates,
            List<String> seeds) {
        var simulator = lanes.simulator().orElseThrow(() -> new CampaignPlayRefusedException(SIMULATOR_DISABLED));
        var entries = candidates.stream().map(this::searchEntry).toList();
        var tables = new ArrayList<ObjectNode>();
        for (var index = 0; index < candidates.size(); index++) {
            tables.add(uniformTable(definition, candidates.get(index).id(), entries.get(index)));
        }
        try {
            return experiments.freeze(
                    idempotencyKey,
                    huntManifest(
                            definition, definition.name() + ", wave " + waveIndex, entries, tables, seeds, simulator));
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public int newestPolicyParameterSpaceVersion() {
        return catalog.parameterSpaces().stream()
                .mapToInt(PolicyParameterSpaceDescriptor::version)
                .max()
                .orElse(1);
    }

    @Override
    public UUID freezeHuntConfirmation(
            UUID idempotencyKey, CampaignDefinition definition, Candidate candidate, List<String> seeds) {
        var simulator = lanes.simulator().orElseThrow(() -> new SimulatorNotAvailableException(SIMULATOR_DISABLED));
        var standard = bundles(List.of(STANDARD)).getFirst();
        var entry = searchEntry(candidate);
        var manifest = huntManifest(
                definition,
                definition.name() + ", confirmation of " + candidate.id(),
                List.of(entry, standard),
                List.of(
                        uniformTable(definition, HuntPlan.CANDIDATE_TABLE, entry),
                        uniformTable(definition, HuntPlan.STANDARD_TABLE, standard)),
                seeds,
                simulator);
        try {
            return experiments.freeze(builtOn(idempotencyKey, simulator), manifest);
        } catch (ExperimentRefusedException e) {
            throw new CampaignPlayRefusedException(e.getMessage(), e);
        }
    }
}
