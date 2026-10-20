package io.github.temporalrift.workbench.execution.simulator;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.regex.Pattern;

import org.springframework.test.context.DynamicPropertyRegistry;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

/**
 * The builds the real-service simulator tests host. {@value #DIRECTORY_PROPERTY} names a directory holding the
 * executable jars as {@code game-service.jar}, {@code timeline-service.jar} and {@code read-service.jar}, built from
 * the revisions named by {@value #REVISIONS_PROPERTY} (game, timeline and read, comma separated, each 7 to 64
 * lowercase hex digits). The revisions default to placeholders for tests that record no provenance.
 */
public final class SimulatorBuilds {

    public static final String DIRECTORY_PROPERTY = "simulator.builds";
    public static final String REVISIONS_PROPERTY = "simulator.revisions";
    static final String RULES_DIGEST = VariantRulesFixtures.baseline().digest();
    static final String CONTENT_DIGEST = "b".repeat(64);

    private static final Path RULES = Path.of("src/test/resources/simulator/baseline-rules.yml");
    private static final Path RULES_DOCUMENT = Path.of("src/test/resources/rules/baseline-effective-rules.json");
    private static final String PLACEHOLDER_REVISIONS = "0000000,0000000,0000000";
    private static final Pattern REVISION = Pattern.compile("[0-9a-f]{7,64}");

    private SimulatorBuilds() {}

    /** The jar of a service ({@code game-service}, {@code timeline-service} or {@code read-service}). */
    public static Path jar(String service) {
        var directory = System.getProperty(DIRECTORY_PROPERTY);
        if (directory == null || directory.isBlank()) {
            throw new IllegalStateException("-D" + DIRECTORY_PROPERTY + " must name the directory of the service jars");
        }
        return Path.of(directory).resolve(service + ".jar");
    }

    /** The game configuration every service of a slot plays under. */
    public static Path rules() {
        return RULES.toAbsolutePath();
    }

    /** The baseline {@code EffectiveRules} document of {@link #rules()}, whose digest is {@link #RULES_DIGEST}. */
    public static Path rulesDocument() {
        return RULES_DOCUMENT.toAbsolutePath();
    }

    /** The manifest with its variants naming the bundle the simulator serves. */
    public static JsonNode served(JsonNode manifest) {
        return ExperimentManifests.servedBy(manifest, RULES_DIGEST, CONTENT_DIGEST);
    }

    /** The source revisions of the game, timeline and read services. */
    static String[] revisions() {
        var revisions = Arrays.stream(System.getProperty(REVISIONS_PROPERTY, PLACEHOLDER_REVISIONS)
                        .split(",", -1))
                .map(String::trim)
                .toArray(String[]::new);
        if (revisions.length != 3
                || !Arrays.stream(revisions)
                        .allMatch(revision -> REVISION.matcher(revision).matches())) {
            throw new IllegalStateException("-D" + REVISIONS_PROPERTY
                    + " must be <game>,<timeline>,<read>, each 7 to 64 lowercase hex digits, but was "
                    + System.getProperty(REVISIONS_PROPERTY));
        }
        return revisions;
    }

    static void declare(DynamicPropertyRegistry registry, int bots) {
        var revisions = revisions();
        registry.add("workbench.simulator.game-service-jar", () -> jar("game-service"));
        registry.add("workbench.simulator.timeline-service-jar", () -> jar("timeline-service"));
        registry.add("workbench.simulator.read-service-jar", () -> jar("read-service"));
        registry.add("workbench.simulator.rules-file", SimulatorBuilds::rules);
        registry.add("workbench.simulator.bots", () -> bots);
        registry.add("workbench.simulator.bundle.rules-digest", () -> RULES_DIGEST);
        registry.add("workbench.simulator.bundle.rules-document", SimulatorBuilds::rulesDocument);
        registry.add("workbench.simulator.bundle.content-digest", () -> CONTENT_DIGEST);
        registry.add("workbench.simulator.bundle.rules-artifact-digest", () -> "7".repeat(64));
        registry.add("workbench.simulator.bundle.content-artifact-digest", () -> "d".repeat(64));
        registry.add("workbench.simulator.bundle.source-revisions.game-service", () -> revisions[0]);
        registry.add("workbench.simulator.bundle.source-revisions.timeline-service", () -> revisions[1]);
        registry.add("workbench.simulator.bundle.source-revisions.read-service", () -> revisions[2]);
        registry.add("workbench.simulator.bundle.contracts.session-api", () -> "3.0.0");
        registry.add("workbench.simulator.bundle.contracts.action-api", () -> "7.1.0");
    }
}
