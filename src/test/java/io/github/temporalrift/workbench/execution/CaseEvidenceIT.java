package io.github.temporalrift.workbench.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.execution.application.port.in.CaseView;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.reproduction.ReproductionState;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.CaseState;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;

@WorkbenchIntegrationTest
class CaseEvidenceIT {

    private static final String WORKER = "evidence-worker";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    @Autowired
    private GetCaseUseCase getCase;

    @Autowired
    private GetCaseReplayUseCase getReplay;

    @Autowired
    private ReproduceCaseUseCase reproduceCase;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private RunReproductionUseCase reproductions;

    @Autowired
    private ReproductionRepository reproductionRepository;

    @Autowired
    private EvidenceLedger evidence;

    @Autowired
    private ScriptedLanes lanes;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void cleanRuns() {
        jdbc.update("DELETE FROM evidence_event");
        jdbc.update("DELETE FROM evidence_step");
        jdbc.update("DELETE FROM evidence_source_offset");
        jdbc.update("DELETE FROM case_evidence");
        jdbc.update("DELETE FROM evidence_artifact");
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
    }

    @Test
    void aFinishedCaseRetainsEveryCommandItsObservationItsTranscriptAndItsPinnedManifest() {
        var saved = savedCase();

        var steps = evidence.steps(saved.caseId(), saved.gameId());
        assertThat(steps).hasSize(6);
        assertThat(steps).allSatisfy(step -> assertThat(step.outcome()).isEqualTo(StepOutcome.ACCEPTED));
        assertThat(steps).extracting(step -> step.phase()).containsOnly("HAND_SELECTION", "TERMINAL_READINESS");
        assertThat(steps).extracting(step -> step.step()).containsExactly(0, 1, 2, 3, 4, 5);
        var pinned = evidence.pinned(saved.caseId()).orElseThrow();
        assertThat(pinned.manifestDigest()).isEqualTo(saved.manifestDigest());
        assertThat(pinned.manifestJson()).contains("\"seeds\"");
        assertThat(pinned.resultDigest()).isEqualTo(saved.semanticDigest());
        assertThat(pinned.transcript().lines()).hasSize(3);
    }

    @Test
    void aFailedAttemptKeepsItsEvidenceNextToTheGameThatCounts() {
        var failFirstAttempt = new AtomicBoolean(true);
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public void onPoll(
                    CaseLane.CaseContext context, io.github.temporalrift.workbench.execution.support.FakeGame game) {
                if (game.undecided().isEmpty() && failFirstAttempt.compareAndSet(true, false)) {
                    throw new AttemptFailedException(FailureCode.EXECUTION_FAILED, "the lane dropped");
                }
            }
        });
        var runId = start();
        batch.maintain();
        batch.runNextCase(WORKER);
        lanes.forgetGames();
        batch.runNextCase(WORKER);

        var caseId = caseIds(runId).getFirst();
        var view = getCase.handle(runId, caseId);
        assertThat(view.attempts()).hasSize(2);
        assertThat(view.attempts().get(0).state()).isEqualTo(AttemptState.FAILED);
        var failedGame = view.attempts().get(0).gameId();
        var countedGame = view.attempts().get(1).gameId();
        assertThat(failedGame).isNotEqualTo(countedGame);
        assertThat(evidence.steps(caseId, failedGame)).isNotEmpty();
        assertThat(evidence.steps(caseId, countedGame)).hasSize(6);
        var replay = getReplay.handle(new GetCaseReplayUseCase.Query(
                runId, caseId, GetCaseReplayUseCase.Perspective.OBSERVER, null, null, 100));
        assertThat(replay.entries()).hasSize(6);
    }

    @Test
    void aPlayerReplayShowsOnlyThatSeatsFactsAndOnlyAfterTheyAreEarned() throws Exception {
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            /** Seat 0 earns exact weights (as through Intercept) only when its terminal window opens. */
            @Override
            public EntitledObservation observation(CaseLane.CaseContext context, EntitledObservation observation) {
                if (observation.seatIndex() != 0
                        || !(observation.window() instanceof DecisionWindow.TerminalReadiness)) {
                    return observation;
                }
                var events = observation.events().stream()
                        .map(event -> new io.github.temporalrift.workbench.policy.domain.observation.EventView(
                                event.eventId(),
                                event.outcomes().stream()
                                        .map(outcome ->
                                                new OutcomeView(outcome.outcomeId(), outcome.printedWeight(), 77))
                                        .toList()))
                        .toList();
                return new EntitledObservation(
                        observation.seatIndex(),
                        observation.faction(),
                        events,
                        observation.otherPlayerIds(),
                        observation.window());
            }
        });
        var saved = savedCase();

        var seat0 = replay(saved, "PLAYER", 0, "simulation:read").andExpect(status().isOk());
        var seat1 = replay(saved, "PLAYER", 1, "simulation:read").andExpect(status().isOk());

        var first = steps(seat0).get(0);
        var last = steps(seat0).get(steps(seat0).size() - 1);
        assertThat(steps(seat0)).hasSize(2);
        assertThat(first.at("/observations/events/0/outcomes/0/scannedWeight").asInt(-1))
                .isEqualTo(-1);
        assertThat(last.at("/observations/events/0/outcomes/0/scannedWeight").asInt(-1))
                .isEqualTo(77);
        seat1.andExpect(content().string(not(containsString("77"))));
        for (var seat : List.of(0, 1)) {
            var body = replay(saved, "PLAYER", seat, "simulation:read")
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            // The other seats hand ids are (seat, slot) pairs; a seat never sees the others, nor their factions.
            for (var other : List.of(0, 1, 2)) {
                if (other != seat) {
                    assertThat(body).doesNotContain(new UUID(other, 1).toString());
                }
            }
            assertThat(body).contains(new UUID(seat, 1).toString());
        }
        assertThat(first.at("/observations/faction").asString()).isEqualTo("ERASERS");
        assertThat(steps(seat1).get(0).at("/observations/faction").asString()).isEqualTo("PROPHETS");
    }

    @Test
    void anObserverReplayNeedsTheObserveScopeAndShowsEverySeat() throws Exception {
        var saved = savedCase();

        replay(saved, "OBSERVER", null, "simulation:read")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));

        var observer = replay(saved, "OBSERVER", null, "simulation:read", "simulation:observe")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perspective").value("OBSERVER"))
                .andExpect(jsonPath("$.manifestDigest").value(saved.manifestDigest()));
        var all = steps(observer);
        assertThat(all)
                .extracting(step -> step.at("/decision/seatIndex").asInt())
                .contains(0, 1, 2);
    }

    @Test
    void aPlayerReplayNeedsASeatAndAnObserverReplayForbidsOne() throws Exception {
        var saved = savedCase();

        replay(saved, "PLAYER", null, "simulation:read")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_PERSPECTIVE"));
        replay(saved, "PLAYER", 7, "simulation:read")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_PERSPECTIVE"));
        replay(saved, "OBSERVER", 0, "simulation:read", "simulation:observe")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REPLAY_PERSPECTIVE"));
    }

    @Test
    void replayPagesContinueFromTheLastStepSeen() {
        var saved = savedCase();
        var query = new GetCaseReplayUseCase.Query(
                saved.runId(), saved.caseId(), GetCaseReplayUseCase.Perspective.OBSERVER, null, null, 4);

        var first = getReplay.handle(query);
        assertThat(first.entries()).extracting(entry -> entry.step()).containsExactly(0, 1, 2, 3);
        assertThat(first.nextStep()).isEqualTo(3);

        var second = getReplay.handle(new GetCaseReplayUseCase.Query(
                saved.runId(), saved.caseId(), GetCaseReplayUseCase.Perspective.OBSERVER, null, first.nextStep(), 4));
        assertThat(second.entries()).extracting(entry -> entry.step()).containsExactly(4, 5);
        assertThat(second.nextStep()).isNull();
    }

    @Test
    void aSavedCaseReproducesWithAMatchAndAddsNoResearchSample() throws Exception {
        var saved = savedCase();
        var attemptsBefore = jdbc.queryForObject("SELECT count(*) FROM case_attempt", Integer.class);
        var key = UUID.randomUUID();

        var accepted = reproduce(saved, key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.firstDivergence").doesNotExist());
        var reproductionId =
                UUID.fromString(json(accepted).get("reproductionId").asString());
        var attemptId = UUID.fromString(json(accepted).get("attemptId").asString());
        assertThat(attemptId).isNotEqualTo(saved.attemptId());

        assertThat(reproductions.runNext(WORKER)).isTrue();

        reproduce(saved, key)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.reproductionId").value(reproductionId.toString()))
                .andExpect(jsonPath("$.attemptId").value(attemptId.toString()))
                .andExpect(jsonPath("$.state").value("MATCH"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction", Integer.class))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM case_attempt", Integer.class))
                .isEqualTo(attemptsBefore);
        var after = getCase.handle(saved.runId(), saved.caseId());
        assertThat(after.logicalCase().state()).isEqualTo(CaseState.SUCCEEDED);
        assertThat(after.logicalCase().result().semanticDigest()).isEqualTo(saved.semanticDigest());
        assertThat(after.attempts()).hasSize(1);
        assertThat(reproductionRepository.find(reproductionId).orElseThrow().state())
                .isEqualTo(ReproductionState.MATCH);
        var original = evidence.steps(saved.caseId(), saved.gameId());
        var reproduced = jdbc.queryForObject(
                "SELECT count(*) FROM evidence_step WHERE scope_id = ?", Integer.class, reproductionId);
        assertThat(reproduced).isEqualTo(original.size());
    }

    @Test
    void theSameKeyForAnotherCaseConflicts() throws Exception {
        var saved = savedCase();
        var key = UUID.randomUUID();
        reproduce(saved, key).andExpect(status().isAccepted());
        var other = jdbc.queryForObject(
                "SELECT case_id FROM run_case WHERE run_id = ? AND case_id <> ? LIMIT 1",
                UUID.class,
                saved.runId(),
                saved.caseId());

        mockMvc.perform(post("/api/v1/runs/{runId}/cases/{caseId}/reproductions", saved.runId(), other)
                        .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                        .header("Idempotency-Key", key))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aReproductionMatchesAfterARestartAndAlongsideAnUnrelatedLane() {
        var saved = savedCase();
        var reproduction = reproduceCase.handle(
                new ReproduceCaseUseCase.Command(saved.runId(), saved.caseId(), UUID.randomUUID()));
        // The process died holding the claim: nothing was settled and the lane was never released.
        reproductionRepository.claimNext(
                "crashed-worker",
                java.time.Instant.now(),
                java.time.Instant.now().plusSeconds(60));
        assertThat(reproductionRepository
                        .find(reproduction.reproductionId())
                        .orElseThrow()
                        .state())
                .isEqualTo(ReproductionState.RUNNING);
        reproductions.recoverAfterRestart();
        assertThat(reproductionRepository
                        .find(reproduction.reproductionId())
                        .orElseThrow()
                        .state())
                .isEqualTo(ReproductionState.QUEUED);

        var unrelated = start();
        batch.maintain();
        var ranUnrelated = new AtomicInteger();
        var nested = new AtomicBoolean();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            /** While the reproduction holds one lane, an unrelated case plays on the other. */
            @Override
            public void onPoll(
                    CaseLane.CaseContext context, io.github.temporalrift.workbench.execution.support.FakeGame game) {
                if (context.caseId().equals(reproduction.reproductionId()) && nested.compareAndSet(false, true)) {
                    if (batch.runNextCase(WORKER)) {
                        ranUnrelated.incrementAndGet();
                    }
                }
            }
        });

        assertThat(reproductions.runNext(WORKER)).isTrue();

        assertThat(ranUnrelated.get()).isEqualTo(1);
        assertThat(reproductionRepository
                        .find(reproduction.reproductionId())
                        .orElseThrow()
                        .state())
                .isEqualTo(ReproductionState.MATCH);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM run_case WHERE run_id = ? AND state = 'SUCCEEDED'",
                        Integer.class,
                        unrelated))
                .isEqualTo(1);
    }

    @Test
    void aReproductionWhoseGameDiffersReportsTheFirstSemanticDivergence() {
        var saved = savedCase();
        lanes.script(context -> ScriptedLanes.withoutWinners(context, EndReason.RESOLUTION_FAILED));

        var reproduction = reproduce(saved);

        assertThat(reproduction.state()).isEqualTo(ReproductionState.DIVERGED);
        var divergence = reproduction.firstDivergence();
        assertThat(divergence.kind()).isEqualTo("END_REASON");
        assertThat(divergence.step()).isEqualTo(6);
        assertThat(divergence.expected()).containsEntry("endReason", "WIN_CONDITION_MET");
        assertThat(divergence.actual()).containsEntry("endReason", "RESOLUTION_FAILED");
    }

    @Test
    void aReproductionWhoseObservationDriftsDivergesAtThatStep() {
        var saved = savedCase();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public EntitledObservation observation(CaseLane.CaseContext context, EntitledObservation observation) {
                if (observation.seatIndex() != 1 || !(observation.window() instanceof DecisionWindow.HandSelection)) {
                    return observation;
                }
                var events = observation.events().stream()
                        .map(event -> new io.github.temporalrift.workbench.policy.domain.observation.EventView(
                                event.eventId(),
                                event.outcomes().stream()
                                        .map(outcome -> new OutcomeView(outcome.outcomeId(), 50, null))
                                        .toList()))
                        .toList();
                return new EntitledObservation(
                        observation.seatIndex(),
                        observation.faction(),
                        events,
                        observation.otherPlayerIds(),
                        observation.window());
            }
        });

        var reproduction = reproduce(saved);

        assertThat(reproduction.state()).isEqualTo(ReproductionState.DIVERGED);
        assertThat(reproduction.firstDivergence().kind()).isEqualTo("OBSERVATION");
        assertThat(reproduction.firstDivergence().step()).isEqualTo(1);
        assertThat(reproduction.firstDivergence().expected()).containsEntry("seatIndex", 1);
    }

    @Test
    void aTranscriptDecisionTheServiceNowRefusesIsReportedNotReplacedByAnotherChoice() {
        var saved = savedCase();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public String rejection(CaseLane.CaseContext context, int seatIndex) {
                return seatIndex == 2 ? "422-11" : null;
            }
        });

        var reproduction = reproduce(saved);

        assertThat(reproduction.state()).isEqualTo(ReproductionState.DIVERGED);
        var divergence = reproduction.firstDivergence();
        assertThat(divergence.kind()).isEqualTo("COMMAND_REJECTED");
        assertThat(divergence.expected()).containsEntry("seatIndex", 2).containsEntry("outcome", "ACCEPTED");
        assertThat(divergence.actual()).containsEntry("outcome", "REJECTED");
    }

    @Test
    void aReproductionThatCannotRunToAnEndingFailsInsteadOfClaimingAMatch() {
        var saved = savedCase();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public void onOpen(CaseLane.CaseContext context) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT, "the lane serves another rules bundle");
            }
        });

        var reproduction = reproduce(saved);

        assertThat(reproduction.state()).isEqualTo(ReproductionState.FAILED);
        assertThat(reproduction.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT);
        assertThat(reproduction.firstDivergence()).isNull();
    }

    @Test
    void tamperedOrUnavailablePinnedArtifactsAreRefusedWithAManifestMismatch() throws Exception {
        var saved = savedCase();
        jdbc.update(
                "UPDATE evidence_artifact SET content = ? WHERE digest = (SELECT transcript_artifact FROM"
                        + " case_evidence WHERE scope_id = ?)",
                "tampered".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                saved.caseId());

        reproduce(saved, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_MISMATCH"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM reproduction", Integer.class))
                .isZero();
    }

    @Test
    void aManifestThatIsNoLongerTheFrozenOneIsARefusedWithAManifestMismatch() throws Exception {
        var saved = savedCase();
        jdbc.update("UPDATE case_evidence SET manifest_digest = ? WHERE scope_id = ?", "f".repeat(64), saved.caseId());

        reproduce(saved, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_MISMATCH"));
    }

    @Test
    void aCaseWithNothingSavedCannotBeReproduced() throws Exception {
        var runId = start();
        var caseId = caseIds(runId).getFirst();
        var unfinished = new SavedCase(runId, caseId, null, null, null, null);

        reproduce(unfinished, UUID.randomUUID())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("MANIFEST_MISMATCH"));
    }

    @Test
    void aFinalScoreThatArrivesLateKeepsTheCaseIncompleteInsteadOfRecordingAZeroScoreGame() {
        var runId = start();
        var caseId = caseIds(runId).getFirst();
        var checks = new AtomicInteger();
        var readWhileWaiting = new java.util.concurrent.atomic.AtomicReference<CaseView>();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            @Override
            public boolean endingPublished(CaseLane.CaseContext context) {
                if (checks.getAndIncrement() == 0) {
                    readWhileWaiting.set(getCase.handle(runId, caseId));
                    return false;
                }
                return true;
            }
        });
        batch.maintain();

        batch.runNextCase(WORKER);

        var waiting = readWhileWaiting.get();
        assertThat(waiting.logicalCase().state()).isEqualTo(CaseState.RUNNING);
        assertThat(waiting.logicalCase().result()).isNull();
        var done = getCase.handle(runId, caseId);
        assertThat(done.logicalCase().state()).isEqualTo(CaseState.SUCCEEDED);
        assertThat(done.logicalCase().result().finalScores())
                .extracting(score -> score.score())
                .containsExactly(30, 26, 22);
    }

    private SavedCase savedCase() {
        var runId = start();
        batch.maintain();
        while (batch.runNextCase(WORKER)) {
            // run every case
        }
        batch.maintain();
        var caseId = caseIds(runId).getFirst();
        var view = getCase.handle(runId, caseId);
        var attempt = view.attempts().getFirst();
        var manifestDigest = evidence.pinned(caseId).orElseThrow().manifestDigest();
        return new SavedCase(
                runId,
                caseId,
                attempt.attemptId(),
                attempt.gameId(),
                manifestDigest,
                view.logicalCase().result().semanticDigest());
    }

    private UUID start() {
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(
                        UUID.randomUUID(), ExperimentManifests.threePlayerSingleSet()))
                .experimentId();
        return startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
    }

    private List<UUID> caseIds(UUID runId) {
        return jdbc.queryForList("SELECT case_id FROM run_case WHERE run_id = ? ORDER BY ordinal", UUID.class, runId);
    }

    /** Requests a reproduction and runs it on a worker, returning the settled reproduction. */
    private io.github.temporalrift.workbench.execution.domain.reproduction.Reproduction reproduce(SavedCase saved) {
        var queued = reproduceCase.handle(
                new ReproduceCaseUseCase.Command(saved.runId(), saved.caseId(), UUID.randomUUID()));
        assertThat(reproductions.runNext(WORKER)).isTrue();
        return reproductionRepository.find(queued.reproductionId()).orElseThrow();
    }

    private ResultActions reproduce(SavedCase saved, UUID key) throws Exception {
        return mockMvc.perform(post("/api/v1/runs/{runId}/cases/{caseId}/reproductions", saved.runId(), saved.caseId())
                .with(jwt().authorities(new SimpleGrantedAuthority("SCOPE_simulation:write")))
                .header("Idempotency-Key", key));
    }

    private ResultActions replay(SavedCase saved, String perspective, Integer seat, String... scopes) throws Exception {
        java.util.List<org.springframework.security.core.GrantedAuthority> authorities = java.util.Arrays.stream(scopes)
                .<org.springframework.security.core.GrantedAuthority>map(
                        scope -> new SimpleGrantedAuthority("SCOPE_" + scope))
                .toList();
        var request = get("/api/v1/runs/{runId}/cases/{caseId}/replay", saved.runId(), saved.caseId())
                .param("perspective", perspective)
                .with(jwt().authorities(authorities));
        if (seat != null) {
            request = request.param("seatIndex", seat.toString());
        }
        return mockMvc.perform(request);
    }

    private JsonNode json(ResultActions actions) throws Exception {
        return objectMapper.readTree(actions.andReturn().getResponse().getContentAsString());
    }

    private List<JsonNode> steps(ResultActions actions) throws Exception {
        var steps = json(actions).get("steps");
        var list = new java.util.ArrayList<JsonNode>();
        steps.forEach(list::add);
        return list;
    }

    private record SavedCase(
            UUID runId, UUID caseId, UUID attemptId, UUID gameId, String manifestDigest, String semanticDigest) {}
}
