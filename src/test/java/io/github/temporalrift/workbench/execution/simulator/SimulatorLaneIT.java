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

/**
 * One slot hosting the real services: a seeded case reaches an authoritative ending, and replays identically. The first
 * run plays on services the slot just started and the second on the same services after their contexts were closed, so
 * equal digests also show that a closed context leaves nothing that changes the next game.
 */
@SpringBootTest(properties = "workbench.simulator.slots=1")
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestSecurityConfig.class})
@EnabledIfSystemProperty(named = SimulatorBuilds.DIRECTORY_PROPERTY, matches = ".+")
class SimulatorLaneIT {

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
    void aThreePlayerCaseWithSeedFortyTwoEndsAuthoritativelyAndPlaysTheSameTwice() {
        var manifest = SimulatorBuilds.served(ExperimentManifests.threePlayerSingleSet());

        var first = play.start(manifest);
        play.drain(first, 1);
        var second = play.start(manifest);
        play.drain(second, 1);

        assertThat(play.states(first).values())
                .as("failures: %s", play.failures(first))
                .hasSize(3)
                .containsOnly("SUCCEEDED");
        assertThat(play.states(second).values())
                .as("failures: %s", play.failures(second))
                .hasSize(3)
                .containsOnly("SUCCEEDED");
        assertThat(play.lanes(first)).containsExactly("simulator-1");
        var digests = play.digestsByCaseKey(first);
        play.digestsByCaseKey(second)
                .forEach((caseKey, digest) -> assertThat(digest)
                        .as("case %s: %s", caseKey, play.divergence(first, second, caseKey))
                        .isEqualTo(digests.get(caseKey)));
    }
}
