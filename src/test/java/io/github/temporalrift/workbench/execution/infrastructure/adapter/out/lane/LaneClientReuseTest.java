package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.CaseRules;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionState;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.SessionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.CreateLobbyResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.session.model.StartGameResponse;
import io.github.temporalrift.workbench.execution.support.InMemoryCommandLedger;
import io.github.temporalrift.workbench.execution.support.InMemoryEvidenceLedger;
import io.github.temporalrift.workbench.execution.support.Provenances;
import io.github.temporalrift.workbench.execution.support.RecordingContexts;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;
import io.github.temporalrift.workbench.shared.game.Faction;

class LaneClientReuseTest {

    private static final BundleDigests BUNDLE = new BundleDigests("a".repeat(64), "b".repeat(64));
    private static final UUID CASE_KEY = new UUID(2, 2);
    private static final String DIGEST = "c".repeat(64);
    private static final String RULES_DIGEST = "e".repeat(64);
    private static final UUID BOT = new UUID(0, 1);
    private static final UUID LOBBY = new UUID(8, 8);
    private static final UUID GAME = new UUID(9, 9);

    private final Map<String, Integer> built = new HashMap<>();

    @Test
    void tenCasesOnOneLaneBuildEachClientOnce() {
        var endpoints = new LaneEndpoints(
                "lane-1",
                "http://game",
                "http://timeline",
                "http://read",
                "operator",
                BUNDLE,
                Provenances.sample(),
                "game.events",
                "timeline.events",
                null,
                null,
                List.of(new LaneEndpoints.BotIdentity(BOT, "t1")));
        var roster = new LaneRoster(
                List.of(endpoints.member()), new RecordingContexts(), (_, _, _) -> {}, (task, _) -> task.run());
        var pool = new ConfiguredLanePool(
                List.of(endpoints),
                roster,
                this::build,
                new LaneServices(
                        new InMemoryCommandLedger(),
                        new InMemoryEvidenceLedger(),
                        (_, _, _, _, _) -> new StubEventObserver()),
                Clock.systemUTC(),
                new HttpGameSession.Pacing(
                        new LaneEndpoints.Barrier(Duration.ZERO, Duration.ofSeconds(1)),
                        _ -> {},
                        new LaneWaits(new SimpleMeterRegistry())));

        pool.prepare();
        for (var round = 0; round < 10; round++) {
            var lane = pool.acquire(Provenances.fitOf(BUNDLE), null).orElseThrow();
            lane.open(context(round));
            lane.close();
        }

        assertThat(built)
                .containsOnlyKeys(
                        "SimulationExecutionApi|http://game|operator",
                        "SimulationExecutionApi|http://timeline|operator",
                        "SessionApi|http://game|t1",
                        "ScoringApi|http://game|t1",
                        "ProjectionApi|http://read|t1",
                        "ActionApi|http://game|t1")
                .allSatisfy((_, count) -> assertThat(count).isEqualTo(1));
    }

    private <T> T build(Class<T> api, String baseUrl, String token) {
        built.merge(api.getSimpleName() + "|" + baseUrl + "|" + token, 1, Integer::sum);
        var client = mock(api);
        if (client instanceof SimulationExecutionApi control) {
            var checkpoint = new ExecutionCheckpoint()
                    .caseKey(CASE_KEY)
                    .manifestDigest(DIGEST)
                    .rulesDigest(RULES_DIGEST)
                    .revision(0L)
                    .logicalTime(OffsetDateTime.parse("2026-01-01T00:00:00Z"))
                    .state(ExecutionState.READY)
                    .drained(true)
                    .outboxPending(0)
                    .continuationsPending(0)
                    .dueTimersPending(0);
            when(control.configureSimulationExecution(any())).thenReturn(ResponseEntity.ok(checkpoint));
        }
        if (client instanceof SessionApi session) {
            when(session.createLobby(any())).thenReturn(ResponseEntity.ok(new CreateLobbyResponse(LOBBY, BOT, "JOIN")));
            when(session.startGame(LOBBY)).thenReturn(ResponseEntity.accepted().body(new StartGameResponse(GAME)));
        }
        return api.cast(client);
    }

    private static CaseLane.CaseContext context(int round) {
        return new CaseLane.CaseContext(
                new UUID(1, round),
                new UUID(4, round),
                CASE_KEY,
                "42",
                DIGEST,
                new CaseRules(new TreeMap<>(), RULES_DIGEST),
                List.of(new SeatPlan(0, Faction.ERASERS, "random", "1.0.0")),
                Instant.parse("2026-01-01T00:00:00Z"));
    }
}
