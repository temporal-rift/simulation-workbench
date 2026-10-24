package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionCreation;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionState;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.Failure;
import io.github.temporalrift.workbench.execution.domain.run.Run;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.shared.execution.FailureCode;

@WorkbenchIntegrationTest
class ReproductionRepositoryIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final BundleDigests OTHER_BUNDLE = new BundleDigests("f".repeat(64), "b".repeat(64));
    private static final Map<String, BundleDigests> LANES = Map.of("lane-1", BUNDLE);

    @Autowired
    private ReproductionRepository reproductions;

    @Autowired
    private RunRepository runs;

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID runId;
    private UUID caseId;

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
    }

    @BeforeEach
    void aCase() {
        clean();
        runId = UUID.randomUUID();
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(
                        UUID.randomUUID(), ExperimentManifests.threePlayerSingleSet()))
                .experimentId();
        var run = Run.queued(runId, experimentId, NOW);
        runs.create(
                UUID.randomUUID(),
                "hash",
                run,
                1,
                List.of(new RunRepository.NewCase(UUID.randomUUID(), 0, "baseline", BUNDLE, "42", 3, List.of())));
        caseId = jdbc.queryForObject("SELECT case_id FROM run_case WHERE run_id = ?", UUID.class, runId);
    }

    @Test
    void theSameKeyReturnsTheOriginalReproductionAndTheRequestThatClaimedIt() {
        var key = UUID.randomUUID();
        var first = Reproduction.queued(runId, caseId, "lane-1", NOW);

        assertThat(reproductions.create(key, "hash-a", first)).isInstanceOf(ReproductionCreation.Created.class);
        var second = reproductions.create(key, "hash-b", Reproduction.queued(runId, caseId, "lane-1", NOW));

        assertThat(second).isInstanceOfSatisfying(ReproductionCreation.Existing.class, existing -> {
            assertThat(existing.claim().reproduction().reproductionId()).isEqualTo(first.reproductionId());
            assertThat(existing.claim().requestHash()).isEqualTo("hash-a");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void aClaimIsExclusiveAndOnlyItsOwnerMaySettleIt() {
        var queued = queue();

        var claimed =
                reproductions.claimNext("worker-a", NOW.plusSeconds(30), LANES).orElseThrow();
        assertThat(claimed.reproductionId()).isEqualTo(queued.reproductionId());
        assertThat(claimed.state()).isEqualTo(ReproductionState.RUNNING);
        assertThat(reproductions.claimNext("worker-b", NOW.plusSeconds(30), LANES))
                .isEmpty();

        assertThat(reproductions.settle(queued.reproductionId(), "worker-b", queued.matched(NOW)))
                .isFalse();
        assertThat(reproductions.extendLease(queued.reproductionId(), "worker-b", NOW.plusSeconds(60)))
                .isFalse();
        assertThat(reproductions.settle(queued.reproductionId(), "worker-a", queued.matched(NOW)))
                .isTrue();
        assertThat(reproductions.find(queued.reproductionId()).orElseThrow().state())
                .isEqualTo(ReproductionState.MATCH);
        assertThat(reproductions.settle(queued.reproductionId(), "worker-a", queued.matched(NOW)))
                .isFalse();
    }

    @Test
    void onlyItsOwnLaneStillServingTheCasesBundleClaimsAReproduction() {
        var queued = queue();

        assertThat(reproductions.claimNext("worker", NOW.plusSeconds(30), Map.of("lane-1", OTHER_BUNDLE)))
                .isEmpty();
        assertThat(reproductions.claimNext("worker", NOW.plusSeconds(30), Map.of("lane-2", BUNDLE)))
                .isEmpty();
        assertThat(reproductions.find(queued.reproductionId()).orElseThrow().state())
                .isEqualTo(ReproductionState.QUEUED);
        assertThat(reproductions.claimNext(
                        "worker", NOW.plusSeconds(30), Map.of("lane-1", BUNDLE, "lane-2", OTHER_BUNDLE)))
                .map(Reproduction::reproductionId)
                .contains(queued.reproductionId());
    }

    @Test
    void aDivergenceAndAFailureSurviveStorage() {
        var diverged = queue();
        reproductions.claimNext("worker", NOW.plusSeconds(30), LANES);
        var divergence = new Divergence.Eras(3, 2, 3);
        reproductions.settle(diverged.reproductionId(), "worker", diverged.diverged(divergence, NOW));

        var failed = queue();
        reproductions.claimNext("worker", NOW.plusSeconds(30), LANES);
        reproductions.settle(
                failed.reproductionId(),
                "worker",
                failed.failed(new Failure(FailureCode.RUNNER_TIMEOUT, "too slow"), NOW));

        var stored = reproductions.find(diverged.reproductionId()).orElseThrow();
        assertThat(stored.state()).isEqualTo(ReproductionState.DIVERGED);
        assertThat(stored.firstDivergence()).isEqualTo(divergence);
        var storedFailure = reproductions.find(failed.reproductionId()).orElseThrow();
        assertThat(storedFailure.state()).isEqualTo(ReproductionState.FAILED);
        assertThat(storedFailure.failure()).isEqualTo(new Failure(FailureCode.RUNNER_TIMEOUT, "too slow"));
    }

    @Test
    void anExpiredLeaseQueuesTheReproductionAgainForAnotherWorker() {
        var queued = queue();
        reproductions.claimNext("worker-a", NOW.plusSeconds(30), LANES);

        assertThat(reproductions.requeueExpired(NOW.plusSeconds(10))).isZero();
        assertThat(reproductions.requeueExpired(NOW.plusSeconds(31))).isEqualTo(1);

        assertThat(reproductions.settle(queued.reproductionId(), "worker-a", queued.matched(NOW)))
                .isFalse();
        assertThat(reproductions.claimNext("worker-b", NOW.plusSeconds(30), LANES))
                .isPresent();
    }

    @Test
    void aGracefulStopAndARestartQueueTheHeldReproductionsAgain() {
        queue();
        queue();
        reproductions.claimNext("worker-a", NOW.plusSeconds(30), LANES);
        reproductions.claimNext("worker-b", NOW.plusSeconds(30), LANES);

        assertThat(reproductions.requeueOwnedBy("worker-a")).isEqualTo(1);
        assertThat(reproductions.requeueAllRunning()).isEqualTo(1);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction WHERE state = 'QUEUED'", Integer.class))
                .isEqualTo(2);
    }

    @Test
    void aGivenBackClaimIsQueuedAgain() {
        var queued = queue();
        reproductions.claimNext("worker-a", NOW.plusSeconds(30), LANES);

        reproductions.release(queued.reproductionId(), "worker-a");

        assertThat(reproductions.find(queued.reproductionId()).orElseThrow().state())
                .isEqualTo(ReproductionState.QUEUED);
    }

    private Reproduction queue() {
        var queued = Reproduction.queued(runId, caseId, "lane-1", NOW);
        reproductions.create(UUID.randomUUID(), "hash", queued);
        return queued;
    }
}
