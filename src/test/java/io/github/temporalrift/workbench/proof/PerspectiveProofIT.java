package io.github.temporalrift.workbench.proof;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.support.FakeGame;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;

/** What each perspective of a replay may contain, and that evidence only an observer may see never steers a bot. */
class PerspectiveProofIT extends ProofSupport {

    /** The ids of a seat's hand are (seat, slot) pairs, so one seat's id appears in no other seat's view. */
    private static final String SEAT_0_HAND = new UUID(0, 1).toString();

    private static final String SEAT_1_HAND = new UUID(1, 1).toString();
    private static final String SEAT_2_HAND = new UUID(2, 1).toString();
    private static final String EARNED_WEIGHT = "scannedWeight";

    @Autowired
    private EvidenceLedger evidence;

    @Test
    void aPlayerReplayHidesTheOpposingHandsAndWhatTheSeatHasNotEarned() throws Exception {
        lanes.script(ScriptedLanes.seatZeroEarnsExactWeightAtTheTerminalWindow());
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var caseId = api.caseSummaries(runId).getFirst().path("caseId").asString();

        var seatOne = ProofApi.text(api.replay(runId, caseId, "PLAYER", 1, ProofApi.reader())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.perspective").value("PLAYER")));

        assertThat(seatOne).contains(SEAT_1_HAND).doesNotContain(SEAT_0_HAND, SEAT_2_HAND, EARNED_WEIGHT);
        var seatZero = api.json(
                api.replay(runId, caseId, "PLAYER", 0, ProofApi.reader()).andExpect(status().isOk()));
        var steps = seatZero.path("steps");
        assertThat(steps.size()).isGreaterThan(1);
        assertThat(steps.get(0).toString()).doesNotContain(EARNED_WEIGHT);
        assertThat(steps.get(steps.size() - 1).toString()).contains(EARNED_WEIGHT);
        assertThat(seatZero.toString()).doesNotContain(SEAT_1_HAND, SEAT_2_HAND);
    }

    @Test
    void anObserverReplayNeedsTheObserveScopeAndThenShowsEverySeat() throws Exception {
        var runId = startRun(threePlayerManifest(List.of("42"), false));
        drain();
        var caseId = api.caseSummaries(runId).getFirst().path("caseId").asString();

        var denied = ProofApi.text(
                api.replay(runId, caseId, "OBSERVER", null, ProofApi.reader()).andExpect(status().isForbidden()));
        assertThat(denied).doesNotContain(SEAT_0_HAND, SEAT_1_HAND, SEAT_2_HAND);

        var observer = ProofApi.text(
                api.replay(runId, caseId, "OBSERVER", null, ProofApi.scopes("simulation:read", "simulation:observe"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.perspective").value("OBSERVER")));
        assertThat(observer).contains(SEAT_0_HAND, SEAT_1_HAND, SEAT_2_HAND);
    }

    @Test
    void forgedObserverEvidenceCannotChangeWhatABotDecidesFromTheSameObservationAndSeed() throws Exception {
        var manifest = threePlayerManifest(List.of("42"), false);
        var cleanRun = startRun(manifest);
        drain();
        var clean = decisionsByCaseKey(cleanRun);

        lanes.reset();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            /** Retains events no seat could have been told, claiming to show every hand and the winner. */
            @Override
            public void onPoll(CaseLane.CaseContext context, FakeGame game) {
                var forged = UUID.nameUUIDFromBytes(("forged|" + context.caseId()).getBytes(StandardCharsets.UTF_8));
                evidence.retain(
                        context.caseId(),
                        context.attemptId(),
                        new ObservedEvent(
                                "game.events",
                                0,
                                0,
                                forged,
                                "HandsRevealed",
                                forged,
                                "GAME",
                                game.gameId(),
                                Instant.parse("2026-01-01T00:00:00Z"),
                                1,
                                "{\"hands\":\"every seat\",\"winner\":\"seat 2\"}"));
            }
        });
        var poisonedRun = startRun(manifest);
        drain();

        var poisoned = decisionsByCaseKey(poisonedRun);

        assertThat(poisoned).isNotEmpty().isEqualTo(clean);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM evidence_event WHERE event_type = 'HandsRevealed'", Integer.class))
                .isPositive();
    }

    /** Every accepted decision of every case, in order, by the case's key, as an observer replay lists them. */
    private Map<String, List<JsonNode>> decisionsByCaseKey(UUID runId) throws Exception {
        var decisions = new TreeMap<String, List<JsonNode>>();
        for (var summary : api.caseSummaries(runId)) {
            var replay = api.json(api.replay(
                            runId,
                            summary.path("caseId").asString(),
                            "OBSERVER",
                            null,
                            ProofApi.scopes("simulation:read", "simulation:observe"))
                    .andExpect(status().isOk()));
            var chosen = new ArrayList<JsonNode>();
            // A retained event is an observer-only entry without a decision; only decisions are compared.
            replay.path("steps").forEach(step -> {
                if (!step.path("decision").isNull()) {
                    chosen.add(step.path("decision"));
                }
            });
            decisions.put(summary.path("caseKey").asString(), chosen);
        }
        return decisions;
    }
}
