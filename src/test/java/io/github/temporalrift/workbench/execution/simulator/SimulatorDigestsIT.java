package io.github.temporalrift.workbench.execution.simulator;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

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

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.TestcontainersConfiguration;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

/**
 * Plays the seeded cases of the baseline template for three, four and five players on real services and writes
 * {@code caseKey=semanticDigest} lines, sorted, to the file named by {@value #FILE_PROPERTY}. Run on two builds of the
 * workbench and compare the files to show a change of pacing leaves every digest equal.
 */
@SpringBootTest(properties = "workbench.simulator.slots=3")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
@EnabledIfSystemProperty(named = SimulatorDigestsIT.FILE_PROPERTY, matches = ".+")
class SimulatorDigestsIT {

    static final String FILE_PROPERTY = "simulator.digests";

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
    void everyBaselineTemplateCaseEndsAndItsDigestIsRecorded() throws IOException {
        var run = play.start(SimulatorBuilds.served(ExperimentManifests.baselineTemplateCases()));
        play.drain(run, 3);

        assertThat(play.states(run).values())
                .as("failures: %s", play.failures(run))
                .isNotEmpty()
                .containsOnly("SUCCEEDED");
        var lines = play.digestsByCaseKey(run).entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .sorted()
                .collect(Collectors.joining("\n", "", "\n"));
        Files.writeString(Path.of(System.getProperty(FILE_PROPERTY)), lines);
    }
}
