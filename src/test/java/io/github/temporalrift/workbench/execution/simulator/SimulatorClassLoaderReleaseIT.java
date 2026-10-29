package io.github.temporalrift.workbench.execution.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.util.stream.IntStream;
import javax.management.ObjectName;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.TestcontainersConfiguration;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

/**
 * Plays real games on the simulator and checks what stays in the JVM. A slot keeps its three services across cases, so
 * after a full collection at most three hosted-service class loaders per slot are live, however many cases were
 * played; once the slots stop their idle services, none is left.
 */
@SpringBootTest(
        properties = {
            "workbench.simulator.slots=" + SimulatorClassLoaderReleaseIT.SLOTS,
            "workbench.simulator.idle-timeout=5s"
        })
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
@EnabledIfSystemProperty(named = SimulatorBuilds.DIRECTORY_PROPERTY, matches = ".+")
class SimulatorClassLoaderReleaseIT {

    static final int SLOTS = 2;
    private static final int SEEDS = 4;
    private static final String SERVICE_CLASS_LOADER =
            "io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator.ServiceClassLoader";

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private JdbcTemplate jdbc;

    private SimulatorPlay play;

    @DynamicPropertySource
    static void builds(DynamicPropertyRegistry registry) {
        SimulatorBuilds.declare(registry, 6);
    }

    @BeforeEach
    void cleanRuns() {
        play = new SimulatorPlay(createExperiment, startRun, batch, jdbc);
        play.clean();
    }

    @Test
    void aSlotKeepsThreeServiceLoadersAcrossCasesAndNoneOnceItStops() throws Exception {
        var run = play.start(workload());
        play.drain(run, SLOTS);

        assertThat(play.states(run).values()).hasSize(SEEDS * 3).containsOnly("SUCCEEDED");
        System.gc();
        assertThat(liveServiceClassLoaders())
                .as("service class loaders after %d cases on %d slots", SEEDS * 3, SLOTS)
                .isLessThanOrEqualTo(3L * SLOTS);
        Awaitility.await()
                .atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    System.gc();
                    assertThat(liveServiceClassLoaders())
                            .as("service class loaders left once the idle slots stopped")
                            .isZero();
                });
    }

    /** The loaders the JVM still keeps classes for, as {@code jcmd VM.classloader_stats} lists them. */
    private static long liveServiceClassLoaders() throws Exception {
        var stats = (String) ManagementFactory.getPlatformMBeanServer()
                .invoke(
                        new ObjectName("com.sun.management:type=DiagnosticCommand"),
                        "vmClassloaderStats",
                        new Object[] {new String[0]},
                        new String[] {String[].class.getName()});
        return stats.lines().filter(line -> line.contains(SERVICE_CLASS_LOADER)).count();
    }

    /** {@link #SEEDS} seeds of one three-player faction set, one policy, one variant: three cases a seed. */
    private static ObjectNode workload() {
        var manifest = (ObjectNode) SimulatorBuilds.served(ExperimentManifests.threePlayerSingleSet())
                .deepCopy();
        var values = manifest.arrayNode();
        IntStream.rangeClosed(1, SEEDS).forEach(seed -> values.add(String.valueOf(seed)));
        manifest.set("seeds", values);
        return manifest;
    }
}
