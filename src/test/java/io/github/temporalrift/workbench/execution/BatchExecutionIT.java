package io.github.temporalrift.workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.CancelRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.InvalidRunStateException;
import io.github.temporalrift.workbench.execution.domain.run.RunState;
import io.github.temporalrift.workbench.execution.support.FakeGame;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

@WorkbenchIntegrationTest
class BatchExecutionIT {

    private static final String WORKER = "test-worker";

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    @Autowired
    private GetRunUseCase getRun;

    @Autowired
    private GetCaseUseCase getCase;

    @Autowired
    private CancelRunUseCase cancelRun;

    @Autowired
    private ResumeRunUseCase resumeRun;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private CaseLedger caseLedger;

    @Autowired
    private ScriptedLanes lanes;

    @Autowired
    private Clock clock;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanRuns() {
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
    }

    @Test
    void frozenThreePlayerExperimentRunsToReconciledAuthoritativeResults() {
        var runId = start(ExperimentManifests.threePlayerSingleSet());

        drain();

        var run = getRun.handle(runId);
        assertThat(run.run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.run().finishedAt()).isNotNull();
        assertThat(run.counts().succeeded()).isEqualTo(3);
        assertThat(run.counts().failed()).isZero();
        for (var caseId : caseIds(runId)) {
            var view = getCase.handle(runId, caseId);
            var result = view.logicalCase().result();
            assertThat(view.logicalCase().state()).isEqualTo(CaseState.SUCCEEDED);
            assertThat(view.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.state()).isEqualTo(AttemptState.SUCCEEDED);
                assertThat(attempt.gameId()).isNotNull();
                assertThat(attempt.laneId()).startsWith("lane-");
            });
            assertThat(result.endReason()).isEqualTo(EndReason.WIN_CONDITION_MET);
            assertThat(result.winners()).hasSize(1);
            assertThat(result.finalScores()).hasSize(3);
            assertThat(result.decisions()).isEqualTo(3);
            assertThat(result.semanticDigest()).matches("[a-f0-9]{64}");
        }
    }

    @ParameterizedTest
    @EnumSource(
            value = EndReason.class,
            names = {"ALL_PLAYERS_ABANDONED", "RESOLUTION_FAILED"})
    void abnormalEndingsAreVisibleCaseResultsAndNeverAttemptFailures(EndReason reason) {
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.withoutWinners(context, reason);
            }
        });
        var runId = start(ExperimentManifests.threePlayerSingleSet());

        drain();

        var run = getRun.handle(runId);
        assertThat(run.run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.counts().succeeded()).isEqualTo(3);
        assertThat(run.counts().failed()).isZero();
        for (var caseId : caseIds(runId)) {
            var view = getCase.handle(runId, caseId);
            assertThat(view.logicalCase().result().endReason()).isEqualTo(reason);
            assertThat(view.logicalCase().result().winners()).isEmpty();
            assertThat(view.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.state()).isEqualTo(AttemptState.SUCCEEDED);
                assertThat(attempt.failure()).isNull();
            });
        }
    }

    @Test
    void lostActionResponseIsReconciledAndNothingIsSentTwice() {
        var manifest = ExperimentManifests.threePlayerSingleSet();
        var clean = start(manifest);
        drain();
        var cleanDigests = digestsByCaseKey(clean);

        lanes.reset();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public boolean loseAcknowledgement(CaseLane.CaseContext context, int seatIndex) {
                return seatIndex == 1;
            }
        });
        var lossy = start(manifest);
        drain();

        assertThat(getRun.handle(lossy).counts().succeeded()).isEqualTo(3);
        digestsByCaseKey(lossy).forEach((caseKey, digest) -> {
            assertThat(lanes.submissionsFor(caseKey)).isEqualTo(3);
            assertThat(digest).isEqualTo(cleanDigests.get(caseKey));
        });
        for (var caseId : caseIds(lossy)) {
            assertThat(getCase.handle(lossy, caseId).logicalCase().result().decisions())
                    .isEqualTo(3);
        }
    }

    @Test
    void interruptedAttemptResumesTheSameGameWithTheSameTranscript() {
        var manifest = ExperimentManifests.threePlayerSingleSet();
        var baseline = start(manifest);
        drain();
        var baselineDigests = digestsByCaseKey(baseline);

        lanes.reset();
        var crashed = new AtomicBoolean();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public boolean loseAcknowledgement(CaseLane.CaseContext context, int seatIndex) {
                if (seatIndex == 1 && crashed.compareAndSet(false, true)) {
                    throw new ProcessCrash();
                }
                return false;
            }
        });
        var runId = start(manifest);
        batch.maintain();

        assertThatThrownBy(() -> batch.runNextCase(WORKER)).isInstanceOf(ProcessCrash.class);
        batch.recoverAfterRestart();
        var interrupted = getRun.handle(runId);
        assertThat(interrupted.run().state()).isEqualTo(RunState.INTERRUPTED);
        assertThat(interrupted.counts().running()).isZero();

        resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID()));
        drain();

        var run = getRun.handle(runId);
        assertThat(run.run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.counts().succeeded()).isEqualTo(3);
        digestsByCaseKey(runId).forEach((caseKey, digest) -> {
            assertThat(digest).isEqualTo(baselineDigests.get(caseKey));
            assertThat(lanes.submissionsFor(caseKey)).isEqualTo(3);
        });
        var recovered = caseIds(runId).stream()
                .map(caseId -> getCase.handle(runId, caseId))
                .filter(view -> view.attempts().size() == 2)
                .toList();
        assertThat(recovered).hasSize(1);
        assertThat(recovered.getFirst().attempts())
                .extracting(attempt -> attempt.state())
                .containsExactly(AttemptState.INTERRUPTED, AttemptState.SUCCEEDED);
        assertThat(recovered.getFirst().attempts().get(1).gameId())
                .isEqualTo(recovered.getFirst().attempts().get(0).gameId());
        assertThat(lanes.opened().stream()
                        .filter(opened -> opened.resumeGameId() != null)
                        .count())
                .isEqualTo(1);
    }

    @Test
    void thousandCaseRunInterruptedAfterFourHundredSuccessesKeepsThoseResultsUnchanged() {
        var runId = start(ExperimentManifests.fourPlayerSeeds(50, 4));
        assertThat(getRun.handle(runId).counts().requested()).isEqualTo(1000);
        batch.maintain();
        for (var done = 0; done < 400; done++) {
            assertThat(batch.runNextCase(WORKER)).isTrue();
        }
        var kept = succeededResults(runId);
        assertThat(kept).hasSize(400);
        // A worker that crashed mid-case leaves an attempt running and holding a lease.
        var now = clock.instant();
        var inFlight =
                caseLedger.claimNext("crashed-worker", now, now.plusSeconds(30)).orElseThrow();

        batch.recoverAfterRestart();
        var interrupted = getRun.handle(runId);
        assertThat(interrupted.run().state()).isEqualTo(RunState.INTERRUPTED);
        assertThat(interrupted.counts().succeeded()).isEqualTo(400);
        assertThat(interrupted.counts().pending()).isEqualTo(600);
        assertThat(interrupted.counts().running()).isZero();

        resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID()));
        assertThatThrownBy(() -> resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID())))
                .isInstanceOf(InvalidRunStateException.class);
        drain();

        var finished = getRun.handle(runId);
        assertThat(finished.run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(finished.counts().succeeded()).isEqualTo(1000);
        assertThat(finished.counts().requested()).isEqualTo(1000);
        var all = succeededResults(runId);
        assertThat(all).hasSize(1000);
        kept.forEach((caseId, snapshot) -> assertThat(all.get(caseId)).isEqualTo(snapshot));
        assertThat(jdbc.queryForObject(
                        "SELECT count(DISTINCT case_key) FROM run_case WHERE run_id = ? AND state = 'SUCCEEDED'",
                        Integer.class,
                        runId))
                .isEqualTo(1000);
        var recoveredCase = getCase.handle(runId, inFlight.logicalCase().caseId());
        assertThat(recoveredCase.attempts())
                .extracting(attempt -> attempt.state())
                .containsExactly(AttemptState.INTERRUPTED, AttemptState.SUCCEEDED);
    }

    @Test
    void cancelledTwentyCaseRunKeepsTwelveSuccessesAndMarksTheRestCancelled() throws Exception {
        var runId = start(ExperimentManifests.fourPlayerSeeds(1, 2));
        assertThat(getRun.handle(runId).counts().requested()).isEqualTo(20);
        batch.maintain();
        for (var done = 0; done < 12; done++) {
            assertThat(batch.runNextCase(WORKER)).isTrue();
        }
        var kept = succeededResults(runId);

        var entered = new CountDownLatch(2);
        var release = new CountDownLatch(1);
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public void onPoll(CaseLane.CaseContext context, FakeGame game) {
                entered.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var active =
                    List.of(pool.submit(() -> batch.runNextCase(WORKER)), pool.submit(() -> batch.runNextCase(WORKER)));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();

            var key = UUID.randomUUID();
            var cancelled = cancelRun.handle(new CancelRunUseCase.Command(runId, key));
            assertThat(cancelled.accepted()).isTrue();
            assertThat(cancelled.run().run().state()).isEqualTo(RunState.CANCELLING);
            assertThat(cancelled.run().counts().cancelled()).isEqualTo(6);
            assertThat(cancelled.run().counts().running()).isEqualTo(2);

            release.countDown();
            for (var future : active) {
                future.get(10, TimeUnit.SECONDS);
            }
            batch.maintain();

            var run = getRun.handle(runId);
            assertThat(run.run().state()).isEqualTo(RunState.CANCELLED);
            assertThat(run.run().finishedAt()).isNotNull();
            assertThat(run.counts().succeeded()).isEqualTo(12);
            assertThat(run.counts().cancelled()).isEqualTo(8);
            assertThat(run.counts().pending()).isZero();
            assertThat(run.counts().running()).isZero();
            assertThat(succeededResults(runId)).isEqualTo(kept);
            assertThat(cancelRun
                            .handle(new CancelRunUseCase.Command(runId, UUID.randomUUID()))
                            .accepted())
                    .isFalse();
            assertThat(cancelRun
                            .handle(new CancelRunUseCase.Command(runId, key))
                            .accepted())
                    .isTrue();
            assertThat(getRun.handle(runId).run().state()).isEqualTo(RunState.CANCELLED);
        }
        var cancelledCases = caseIds(runId).stream()
                .map(caseId -> getCase.handle(runId, caseId))
                .filter(view -> view.logicalCase().state() == CaseState.CANCELLED)
                .toList();
        assertThat(cancelledCases).hasSize(8);
        assertThat(cancelledCases)
                .allSatisfy(view -> assertThat(view.logicalCase().result()).isNull());
    }

    @Test
    void resumeIsLegalOnlyFromInterrupted() {
        var runId = start(ExperimentManifests.threePlayerSingleSet());
        assertThatThrownBy(() -> resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID())))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("QUEUED");

        drain();

        assertThat(getRun.handle(runId).run().state()).isEqualTo(RunState.COMPLETED);
        assertThatThrownBy(() -> resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID())))
                .isInstanceOf(InvalidRunStateException.class)
                .hasMessageContaining("COMPLETED");
    }

    @Test
    void deterministicFailuresFailTheCaseAtOnceAndAreRetained() {
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public void onOpen(CaseLane.CaseContext context) {
                throw new AttemptFailedException(FailureCode.CONFIGURATION_DRIFT, "service digest changed");
            }
        });
        var runId = start(ExperimentManifests.threePlayerSingleSet());

        drain();

        var run = getRun.handle(runId);
        assertThat(run.run().state()).isEqualTo(RunState.COMPLETED);
        assertThat(run.counts().failed()).isEqualTo(3);
        assertThat(run.counts().succeeded()).isZero();
        for (var caseId : caseIds(runId)) {
            var view = getCase.handle(runId, caseId);
            assertThat(view.logicalCase().state()).isEqualTo(CaseState.FAILED);
            assertThat(view.logicalCase().result()).isNull();
            assertThat(view.attempts()).singleElement().satisfies(attempt -> {
                assertThat(attempt.state()).isEqualTo(AttemptState.FAILED);
                assertThat(attempt.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT);
            });
        }
    }

    @Test
    void retryableFailuresUseAFreshAttemptUntilTheBudgetIsSpent() {
        var failures = new AtomicInteger();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public void onOpen(CaseLane.CaseContext context) {
                if (failures.getAndIncrement() < 3) {
                    throw new AttemptFailedException(FailureCode.RUNNER_TIMEOUT, "no ending in time");
                }
            }
        });
        var runId = start(ExperimentManifests.threePlayerSingleSet());

        drain();

        var views = caseIds(runId).stream()
                .map(caseId -> getCase.handle(runId, caseId))
                .toList();
        // Three failed openings spread over the cases: one case spent both attempts, one recovered, one was clean.
        assertThat(views)
                .extracting(view -> view.logicalCase().state())
                .containsExactlyInAnyOrder(CaseState.FAILED, CaseState.SUCCEEDED, CaseState.SUCCEEDED);
        assertThat(views.stream().flatMap(view -> view.attempts().stream()).filter(a -> a.failure() != null))
                .hasSize(3)
                .allSatisfy(attempt -> assertThat(attempt.failure().code()).isEqualTo(FailureCode.RUNNER_TIMEOUT));
        assertThat(getRun.handle(runId).counts().failed()).isEqualTo(1);
        assertThat(getRun.handle(runId).counts().succeeded()).isEqualTo(2);
    }

    @Test
    void expiredLeaseInterruptsTheRunAndFencesTheStaleWorker() {
        var runId = start(ExperimentManifests.threePlayerSingleSet());
        batch.maintain();
        var now = clock.instant();
        var zombie = caseLedger.claimNext("zombie", now, now.minusSeconds(1)).orElseThrow();

        batch.maintain();

        assertThat(getRun.handle(runId).run().state()).isEqualTo(RunState.INTERRUPTED);
        var lateResult = new CaseResult(
                EndReason.WIN_CONDITION_MET,
                List.of(),
                List.of(new FinalScore(0, "ERASERS", 1)),
                1,
                1,
                1,
                "f".repeat(64));
        assertThat(caseLedger.succeed(zombie.attempt().attemptId(), "zombie", lateResult, clock.instant()))
                .isFalse();

        resumeRun.handle(new ResumeRunUseCase.Command(runId, UUID.randomUUID()));
        drain();

        var run = getRun.handle(runId);
        assertThat(run.counts().succeeded()).isEqualTo(3);
        var zombieCase = getCase.handle(runId, zombie.logicalCase().caseId());
        assertThat(zombieCase.logicalCase().result().semanticDigest()).isNotEqualTo("f".repeat(64));
        assertThat(zombieCase.attempts())
                .extracting(attempt -> attempt.state())
                .containsExactly(AttemptState.INTERRUPTED, AttemptState.SUCCEEDED);
    }

    @Test
    void concurrentWorkersNeverShareACaseAndRespectTheRunConcurrency() throws Exception {
        var runId = start(ExperimentManifests.threePlayerSingleSet());
        batch.maintain();
        var now = clock.instant();
        var claimed = ConcurrentHashMap.<UUID>newKeySet();
        var ready = new CountDownLatch(8);
        var go = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (var worker = 0; worker < 8; worker++) {
                var owner = "worker-" + worker;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await(10, TimeUnit.SECONDS);
                    caseLedger
                            .claimNext(owner, now, now.plusSeconds(30))
                            .ifPresent(claim -> claimed.add(claim.logicalCase().caseId()));
                    return null;
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            for (var future : futures) {
                future.get(20, TimeUnit.SECONDS);
            }
        }

        // The manifest bounds this run to four concurrent cases and only three exist.
        assertThat(claimed).hasSize(3);
        assertThat(getRun.handle(runId).counts().running()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_attempt WHERE state = 'RUNNING'", Integer.class))
                .isEqualTo(3);
    }

    private UUID start(JsonNode manifest) {
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest))
                .experimentId();
        return startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
    }

    private void drain() {
        batch.maintain();
        while (batch.runNextCase(WORKER)) {
            // keep claiming until nothing is pending
        }
        batch.maintain();
    }

    private List<UUID> caseIds(UUID runId) {
        return jdbc.queryForList("SELECT case_id FROM run_case WHERE run_id = ? ORDER BY ordinal", UUID.class, runId);
    }

    private Map<UUID, String> digestsByCaseKey(UUID runId) {
        return jdbc
                .queryForList(
                        "SELECT case_key, result_json FROM run_case WHERE run_id = ? AND state = 'SUCCEEDED'", runId)
                .stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row.get("case_key"), row -> digestOf((String) row.get("result_json"))));
    }

    private Map<UUID, String> succeededResults(UUID runId) {
        return jdbc
                .queryForList(
                        "SELECT c.case_id, c.result_json, (SELECT count(*) FROM case_attempt a WHERE a.case_id ="
                                + " c.case_id) AS attempts FROM run_case c WHERE c.run_id = ? AND c.state ="
                                + " 'SUCCEEDED'",
                        runId)
                .stream()
                .collect(Collectors.toMap(
                        row -> (UUID) row.get("case_id"), row -> row.get("result_json") + "|" + row.get("attempts")));
    }

    private static String digestOf(String resultJson) {
        var marker = "\"semanticDigest\":\"";
        var start = resultJson.indexOf(marker) + marker.length();
        return resultJson.substring(start, resultJson.indexOf('"', start));
    }

    /** Models the runner process dying: unlike an exception it is never settled by the worker. */
    private static final class ProcessCrash extends Error {
        private static final long serialVersionUID = 1L;
    }
}
