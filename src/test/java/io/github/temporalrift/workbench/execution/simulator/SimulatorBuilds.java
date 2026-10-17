package io.github.temporalrift.workbench.execution.simulator;

import java.nio.file.Path;

import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * The builds the real-service simulator tests host. {@value #DIRECTORY_PROPERTY} names a directory holding the
 * executable jars as {@code game-service.jar}, {@code timeline-service.jar} and {@code read-service.jar}, built from
 * the revisions named by {@value #REVISIONS_PROPERTY} (game, timeline and read, comma separated).
 */
final class SimulatorBuilds {

    static final String DIRECTORY_PROPERTY = "simulator.builds";
    static final String REVISIONS_PROPERTY = "simulator.revisions";
    static final String RULES = "src/test/resources/simulator/baseline-rules.yml";
    static final String RULES_DIGEST = "a".repeat(64);
    static final String CONTENT_DIGEST = "b".repeat(64);

    private SimulatorBuilds() {}

    static void declare(DynamicPropertyRegistry registry, int bots) {
        var directory = Path.of(System.getProperty(DIRECTORY_PROPERTY));
        var revisions = System.getProperty(REVISIONS_PROPERTY, "unknown,unknown,unknown")
                .split(",");
        registry.add("workbench.simulator.game-service-jar", () -> directory.resolve("game-service.jar"));
        registry.add("workbench.simulator.timeline-service-jar", () -> directory.resolve("timeline-service.jar"));
        registry.add("workbench.simulator.read-service-jar", () -> directory.resolve("read-service.jar"));
        registry.add("workbench.simulator.rules-file", () -> Path.of(RULES).toAbsolutePath());
        registry.add("workbench.simulator.bots", () -> bots);
        registry.add("workbench.simulator.bundle.rules-digest", () -> RULES_DIGEST);
        registry.add("workbench.simulator.bundle.content-digest", () -> CONTENT_DIGEST);
        registry.add("workbench.simulator.bundle.rules-artifact-digest", () -> "c".repeat(64));
        registry.add("workbench.simulator.bundle.content-artifact-digest", () -> "d".repeat(64));
        registry.add("workbench.simulator.bundle.source-revisions.game-service", () -> revisions[0]);
        registry.add("workbench.simulator.bundle.source-revisions.timeline-service", () -> revisions[1]);
        registry.add("workbench.simulator.bundle.source-revisions.read-service", () -> revisions[2]);
        registry.add("workbench.simulator.bundle.contracts.session-api", () -> "3.0.0");
        registry.add("workbench.simulator.bundle.contracts.action-api", () -> "7.1.0");
    }
}
