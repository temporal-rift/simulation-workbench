package io.github.temporalrift.workbench.execution.simulator;

import static org.assertj.core.api.Assertions.assertThat;

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

/** Two slots hosting the real services play two cases at once, each in its own slot, and both end. */
@SpringBootTest(properties = "workbench.simulator.slots=2")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
@EnabledIfSystemProperty(named = SimulatorBuilds.DIRECTORY_PROPERTY, matches = ".+")
class SimulatorParallelSlotsIT {

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
    void twoCasesPlayAtOnceInTheirOwnSlots() {
        var run = play.start(SimulatorBuilds.served(ExperimentManifests.threePlayerSingleSet()));

        play.drain(run, 2);

        assertThat(play.states(run).values())
                .as("failures: %s", play.failures(run))
                .hasSize(3)
                .containsOnly("SUCCEEDED");
        assertThat(play.lanes(run)).containsExactly("simulator-1", "simulator-2");
    }
}
