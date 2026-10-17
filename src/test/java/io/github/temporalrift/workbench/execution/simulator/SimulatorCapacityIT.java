package io.github.temporalrift.workbench.execution.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.stream.IntStream;

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
 * Measures the simulator at one slot count: how many games per hour the slots play and how much memory the JVM uses
 * doing it. {@code simulator.capacity.slots} is the slot count and {@code simulator.capacity.seeds} the seeds of the
 * measured workload (three cases per seed, three players each); one case per slot is played first, unmeasured, so
 * the services' classes are loaded. The measurement is written to {@code target/capacity/slots-N.txt}.
 */
@SpringBootTest(properties = "workbench.simulator.slots=${simulator.capacity.slots}")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
@EnabledIfSystemProperty(named = "simulator.capacity.slots", matches = "[1-9][0-9]*")
class SimulatorCapacityIT {

    private static final int SLOTS = Integer.getInteger("simulator.capacity.slots", 1);
    private static final int SEEDS = Integer.getInteger("simulator.capacity.seeds", 8);
    private static final int CASES_PER_SEED = 3;

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
    void playsTheWorkloadAndRecordsGamesPerHourAndMemory() throws Exception {
        play.drain(play.start(workload(1001, SLOTS / CASES_PER_SEED + 1)), SLOTS);
        var memory = ManagementFactory.getMemoryMXBean();
        var peak = new PeakHeap(memory);
        var sampler = Thread.ofPlatform().daemon().start(peak);

        var started = System.nanoTime();
        var run = play.start(workload(1, SEEDS));
        play.drain(run, SLOTS);
        var elapsed = Duration.ofNanos(System.nanoTime() - started);
        sampler.interrupt();

        var cases = SEEDS * CASES_PER_SEED;
        assertThat(play.states(run).values()).hasSize(cases).containsOnly("SUCCEEDED");
        memory.gc();
        var gamesPerHour = cases * 3600.0 / (elapsed.toMillis() / 1000.0);
        var format = "slots=%d cases=%d wallSeconds=%.1f gamesPerHour=%.0f peakHeapMiB=%d heapAfterGcMiB=%d"
                + " committedHeapMiB=%d nonHeapMiB=%d";
        var line = format.formatted(
                SLOTS,
                cases,
                elapsed.toMillis() / 1000.0,
                gamesPerHour,
                peak.maxUsed() >> 20,
                memory.getHeapMemoryUsage().getUsed() >> 20,
                memory.getHeapMemoryUsage().getCommitted() >> 20,
                memory.getNonHeapMemoryUsage().getUsed() >> 20);
        var file = Path.of("target", "capacity", "slots-" + SLOTS + ".txt");
        Files.createDirectories(file.getParent());
        Files.writeString(file, line + System.lineSeparator());
    }

    /** {@code count} consecutive seeds from {@code first} of one three-player faction set, one policy, one variant. */
    private ObjectNode workload(int first, int count) {
        var manifest = (ObjectNode) ExperimentManifests.threePlayerSingleSet().deepCopy();
        var values = manifest.arrayNode();
        IntStream.range(first, first + count).forEach(seed -> values.add(String.valueOf(seed)));
        manifest.set("seeds", values);
        return manifest;
    }

    /** Samples the heap in use until interrupted. */
    private static final class PeakHeap implements Runnable {
        private final java.lang.management.MemoryMXBean memory;
        private volatile long max;

        private PeakHeap(java.lang.management.MemoryMXBean memory) {
            this.memory = memory;
        }

        @Override
        public void run() {
            while (!Thread.currentThread().isInterrupted()) {
                max = Math.max(max, memory.getHeapMemoryUsage().getUsed());
                try {
                    Thread.sleep(250);
                } catch (InterruptedException _) {
                    return;
                }
            }
        }

        long maxUsed() {
            return max;
        }
    }
}
