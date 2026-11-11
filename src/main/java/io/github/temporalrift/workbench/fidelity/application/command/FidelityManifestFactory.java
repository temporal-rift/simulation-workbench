package io.github.temporalrift.workbench.fidelity.application.command;

import java.util.List;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.fidelity.domain.FidelityEnvironment;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * The manifest of the experiment a check plays: the three variants over the simulator's own bundle, every player count
 * and faction set under cyclic rotation, and each baseline policy on every seat, all on one seed.
 */
public final class FidelityManifestFactory {

    private static final int SCHEMA_VERSION = 2;

    private final ObjectMapper mapper;
    private final FidelitySettings settings;

    public FidelityManifestFactory(ObjectMapper mapper, FidelitySettings settings) {
        this.mapper = mapper;
        this.settings = settings;
    }

    /** The manifest of a check; its edge variants name {@code parameterSpaceVersion}, the space the check recorded. */
    public ObjectNode build(
            String seed,
            int parameterSpaceVersion,
            FidelityEnvironments.Simulator simulator,
            List<FidelityVariant> variants,
            List<FidelityExperiments.Policy> policies) {
        var manifest = mapper.createObjectNode();
        manifest.put("schemaVersion", SCHEMA_VERSION);
        manifest.put("name", "simulator fidelity, seed " + seed);
        var variantNodes = manifest.putArray("variants");
        variants.forEach(variant -> variantNodes.add(variant(variant, simulator, parameterSpaceVersion)));
        var environment = simulator.environment();
        var services = manifest.putObject("services");
        services.set("gameService", service(environment.gameService()));
        services.set("timelineService", service(environment.timelineService()));
        services.set("readService", service(environment.readService()));
        var contracts = manifest.putObject("contracts");
        environment.contracts().forEach(contracts::put);
        var policyNodes = manifest.putArray("policies");
        policies.forEach(policy -> policyNodes.add(policy(policy)));
        var tableNodes = manifest.putArray("tables");
        policies.forEach(policy -> tableNodes.add(table(policy)));
        manifest.putArray("seeds").add(seed);
        var counts = manifest.putArray("playerCounts");
        FidelityMatrix.PLAYER_COUNTS.forEach(counts::add);
        var sets = manifest.putArray("factionSets");
        FidelityMatrix.factionSets().forEach(set -> {
            var node = sets.addArray();
            set.stream().map(Faction::name).forEach(node::add);
        });
        manifest.put("seatRotationMode", "CYCLIC");
        manifest.put("timingMode", "LOGICAL");
        manifest.put("concurrency", Math.clamp(simulator.laneCount(), 1, settings.maxConcurrency()));
        manifest.put("caseWallTimeoutSeconds", settings.caseWallTimeoutSeconds());
        manifest.put("maxRejectedCandidatesPerWindow", settings.maxRejectedCandidatesPerWindow());
        return manifest;
    }

    private ObjectNode variant(
            FidelityVariant variant, FidelityEnvironments.Simulator simulator, int parameterSpaceVersion) {
        var node = mapper.createObjectNode();
        node.put("label", variant.label().label());
        node.set("rulesArtifact", artifact(simulator.rulesArtifactDigest(), simulator.rulesArtifactName()));
        node.set("contentArtifact", artifact(simulator.contentArtifactDigest(), simulator.contentArtifactName()));
        node.put("effectiveRulesDigest", variant.effectiveRulesDigest());
        node.put("effectiveContentDigest", simulator.environment().contentDigest());
        if (variant.label() != VariantLabel.BASELINE) {
            var overrides = node.putObject("overrides");
            variant.overrides().forEach((key, value) -> overrides.set(key, value.json()));
            node.put("parameterSpaceVersion", parameterSpaceVersion);
        }
        return node;
    }

    private ObjectNode artifact(String digest, String name) {
        var node = mapper.createObjectNode();
        node.put("digest", digest);
        if (name != null) {
            node.put("name", name);
        }
        return node;
    }

    private ObjectNode service(FidelityEnvironment.ServiceBuild build) {
        var node = mapper.createObjectNode();
        node.put("imageDigest", build.imageDigest());
        node.put("sourceRevision", build.sourceRevision());
        return node;
    }

    private ObjectNode policy(FidelityExperiments.Policy policy) {
        var node = mapper.createObjectNode();
        node.put("id", policy.id());
        node.put("version", policy.version());
        node.put("artifactDigest", policy.artifactDigest());
        node.putObject("parameters");
        return node;
    }

    /** The table that has the policy play every seat, named for the policy. */
    private ObjectNode table(FidelityExperiments.Policy policy) {
        var node = mapper.createObjectNode();
        node.put("id", policy.id());
        var seats = node.putArray("seats");
        for (var seat = 0; seat < FidelityMatrix.MAX_SEATS; seat++) {
            seats.addObject()
                    .put("seatIndex", seat)
                    .put("policyId", policy.id())
                    .put("policyVersion", policy.version());
        }
        return node;
    }
}
