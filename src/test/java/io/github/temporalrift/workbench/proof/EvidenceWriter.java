package io.github.temporalrift.workbench.proof;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Builds the attributable evidence documents the deployed milestone gate consumes. Every input is a response of
 * the published HTTP boundary, so a deployed run can assemble the same documents; nothing is read from storage and
 * nothing time-dependent or secret is written.
 */
final class EvidenceWriter {

    static final int FORMAT_VERSION = 1;

    /** Next to the build's other reports, so CI retains it with them. */
    static final Path DEFAULT_DIRECTORY = Path.of("target", "simulation-evidence");

    private final ObjectMapper objectMapper;
    private final Path directory;

    EvidenceWriter(ObjectMapper objectMapper, Path directory) {
        this.objectMapper = objectMapper;
        this.directory = directory;
    }

    /** The multi-seed sample: the report with the immutable provenance of the experiment that produced it. */
    JsonNode multiSeedSample(JsonNode experiment, JsonNode run, JsonNode report, List<JsonNode> cases) {
        var evidence = header("MULTI_SEED_SAMPLE", experiment);
        evidence.put("runId", run.path("runId").asString());
        evidence.put("analysisVersion", report.path("analysisVersion").asString());
        evidence.put("analysisSeed", report.path("analysisSeed").asString());
        evidence.put("complete", report.path("complete").asBoolean());
        evidence.set("caseCounts", report.path("caseCounts"));
        evidence.set("cohorts", report.path("cohorts"));
        var samples = evidence.putArray("cases");
        cases.forEach(view -> {
            var sample = samples.addObject();
            sample.put("caseKey", view.path("caseKey").asString());
            sample.put("variantLabel", view.path("variantLabel").asString());
            sample.put("seed", view.path("seed").asString());
            sample.put("state", view.path("state").asString());
            sample.put(
                    "semanticDigest", view.path("result").path("semanticDigest").asString(null));
        });
        return evidence;
    }

    /** An exact reproduction: which saved case was re-executed and how the re-execution ended. */
    JsonNode reproduction(JsonNode experiment, JsonNode savedCase, JsonNode reproduction) {
        var evidence = header("EXACT_REPRODUCTION", experiment);
        evidence.put("runId", savedCase.path("runId").asString());
        evidence.put("caseId", savedCase.path("caseId").asString());
        evidence.put("caseKey", savedCase.path("caseKey").asString());
        evidence.put("variantLabel", savedCase.path("variantLabel").asString());
        evidence.put("seed", savedCase.path("seed").asString());
        evidence.put(
                "savedSemanticDigest",
                savedCase.path("result").path("semanticDigest").asString());
        evidence.put("reproductionId", reproduction.path("reproductionId").asString());
        evidence.put("state", reproduction.path("state").asString());
        evidence.set("firstDivergence", reproduction.path("firstDivergence"));
        return evidence;
    }

    Path write(String fileName, JsonNode evidence) throws IOException {
        Files.createDirectories(directory);
        var target = directory.resolve(fileName);
        Files.writeString(
                target,
                objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
                StandardCharsets.UTF_8);
        return target;
    }

    private ObjectNode header(String kind, JsonNode experiment) {
        var manifest = experiment.path("manifest");
        var evidence = objectMapper.createObjectNode();
        evidence.put("formatVersion", FORMAT_VERSION);
        evidence.put("kind", kind);
        evidence.put("experimentId", experiment.path("experimentId").asString());
        evidence.put("manifestDigest", experiment.path("manifestDigest").asString());
        evidence.put("timingMode", manifest.path("timingMode").asString());
        evidence.set("services", manifest.path("services"));
        evidence.set("contracts", manifest.path("contracts"));
        var policies = evidence.putArray("policies");
        manifest.path("policies").forEach(policy -> {
            var entry = policies.addObject();
            entry.put("id", policy.path("id").asString());
            entry.put("version", policy.path("version").asString());
            entry.put("artifactDigest", policy.path("artifactDigest").asString());
        });
        var variants = evidence.putArray("variants");
        manifest.path("variants").forEach(variant -> {
            var entry = variants.addObject();
            entry.put("label", variant.path("label").asString());
            entry.put(
                    "effectiveRulesDigest", variant.path("effectiveRulesDigest").asString());
            entry.put(
                    "effectiveContentDigest",
                    variant.path("effectiveContentDigest").asString());
        });
        return evidence;
    }
}
