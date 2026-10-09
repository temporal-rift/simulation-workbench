package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.game.CardGrade;
import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.CardType;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;
import io.github.temporalrift.workbench.execution.CaseEvidence;
import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunSummary;

class RunSourceAdapterTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final UUID CASE = new UUID(0, 2);
    private static final UUID GAME = new UUID(0, 3);
    private static final UUID ERASER = new UUID(0, 10);
    private static final UUID WEAVER = new UUID(0, 11);
    private static final UUID PROPHET = new UUID(0, 12);
    private static final UUID SCAN = new UUID(0, 20);
    private static final CardKey SCAN_II = new CardKey(CardType.SCAN, CardGrade.II);

    private final RunCase runCase = new RunCase(
            CASE,
            "base",
            "42",
            3,
            List.of(
                    new RunCase.Seat(0, "ERASERS", "random", "1.0.0"),
                    new RunCase.Seat(1, "WEAVERS", "random", "1.0.0"),
                    new RunCase.Seat(2, "PROPHETS", "random", "1.0.0")),
            "SUCCEEDED",
            new RunCase.Result(
                    "WIN_CONDITION_MET",
                    List.of(1),
                    List.of(new RunCase.Score(0, 10), new RunCase.Score(1, 21), new RunCase.Score(2, 7)),
                    3,
                    9,
                    30));

    @Test
    void retainedEvidenceBecomesSeatAttributedTypedFacts() {
        var events = List.of(
                event(
                        "FactionAssigned",
                        "{\"gameId\":\"%s\",\"playerId\":\"%s\",\"faction\":\"ERASERS\"}".formatted(GAME, ERASER)),
                event(
                        "FactionAssigned",
                        "{\"gameId\":\"%s\",\"playerId\":\"%s\",\"faction\":\"WEAVERS\"}".formatted(GAME, WEAVER)),
                event(
                        "FactionAssigned",
                        "{\"gameId\":\"%s\",\"playerId\":\"%s\",\"faction\":\"PROPHETS\"}".formatted(GAME, PROPHET)),
                event(
                        "HandSelected",
                        ("{\"gameId\":\"%s\",\"eraNumber\":1,\"playerId\":\"%s\",\"selectionOrigin\":\"PLAYER\","
                                        + "\"cards\":[%s]}")
                                .formatted(GAME, WEAVER, cards(5))),
                event(
                        "ActionRoundStarted",
                        ("{\"gameId\":\"%s\",\"eraNumber\":1,\"roundNumber\":1,\"timerSeconds\":60,"
                                        + "\"pendingPlayerIds\":[]}")
                                .formatted(GAME)),
                event(
                        "CardPlayed",
                        ("{\"gameId\":\"%s\",\"eraNumber\":1,\"roundNumber\":1,\"playerId\":\"%s\","
                                        + "\"cardInstanceId\":\"%s\",\"cardType\":\"SCAN\",\"grade\":\"II\"}")
                                .formatted(GAME, WEAVER, SCAN)),
                event(
                        "SpecialRejected",
                        ("{\"gameId\":\"%s\",\"eraNumber\":1,\"playerId\":\"%s\",\"specialAction\":\"THREAD\","
                                        + "\"reason\":\"broken\"}")
                                .formatted(GAME, WEAVER)),
                event("ScoresUpdated", "{\"anything\":true}"));
        var steps = List.of(
                step("ACTION_ROUND", "card:" + SCAN + ":-", "ACCEPTED", observation(true)),
                step("ACTION_ROUND", "special:THREAD:-", "REJECTED", observation(true)),
                step("ACTION_ROUND", "special:THREAD:-", "NOT_SPENT", observation(true)));

        var record = adapter(new CaseEvidence(steps, events))
                .record(RUN, adapter(null).cases(RUN).getFirst())
                .orElseThrow();

        assertThat(record.events())
                .containsExactly(
                        new GameEvent.HandKept(
                                1,
                                1,
                                List.of(
                                        new GameEvent.Card(SCAN, SCAN_II),
                                        new GameEvent.Card(new UUID(0, 101), SCAN_II),
                                        new GameEvent.Card(new UUID(0, 102), SCAN_II),
                                        new GameEvent.Card(new UUID(0, 103), SCAN_II),
                                        new GameEvent.Card(new UUID(0, 104), SCAN_II))),
                        new GameEvent.ActionRoundStarted(1, 1),
                        new GameEvent.CardPlayed(1, 1, 1, SCAN, SCAN_II),
                        new GameEvent.SpecialRejected(1, 1, SpecialAction.THREAD));
        assertThat(record.observations())
                .containsExactly(new GameRecord.RoundObservation(
                        1, 1, 1, List.of(new GameRecord.HeldCard(SCAN, SCAN_II, true))));
        assertThat(record.submissions())
                .containsExactly(new GameRecord.SpecialSubmission(1, SpecialAction.THREAD, true));
    }

    @Test
    void anObservationRetainedWithoutItsHandLeavesTheRoundUnobserved() {
        var record = adapter(new CaseEvidence(
                        List.of(step("ACTION_ROUND", "pass", "ACCEPTED", "{\"window\":{\"cards\":[]}}")), List.of()))
                .record(RUN, adapter(null).cases(RUN).getFirst())
                .orElseThrow();

        assertThat(record.observations()).isEmpty();
    }

    @Test
    void casesCarryTheirCoordinateAndOutcomeBySeat() {
        var analyzed = adapter(null).cases(RUN).getFirst();

        assertThat(analyzed.policyId()).isEqualTo("random");
        assertThat(analyzed.outcome().scores()).containsExactly(10, 21, 7);
        assertThat(analyzed.outcome().winnerSeats()).containsExactly(1);
    }

    private RunSourceAdapter adapter(CaseEvidence evidence) {
        return new RunSourceAdapter(
                new RunCatalog() {
                    @Override
                    public Optional<RunSummary> run(UUID runId) {
                        return Optional.of(new RunSummary(RUN, new UUID(0, 9)));
                    }

                    @Override
                    public List<RunCase> cases(UUID runId) {
                        return List.of(runCase);
                    }

                    @Override
                    public Optional<CaseEvidence> evidence(UUID runId, UUID caseId) {
                        return Optional.ofNullable(evidence);
                    }
                },
                JsonMapper.builder().build());
    }

    private static CaseEvidence.Event event(String type, String payload) {
        return new CaseEvidence.Event("game.events", type, payload);
    }

    private static CaseEvidence.Step step(String phase, String decision, String outcome, String observation) {
        return new CaseEvidence.Step(1, phase, 1, 1, observation, decision, outcome, null);
    }

    private static String observation(boolean playable) {
        return ("{\"seatIndex\":1,\"window\":{\"hand\":[{\"card\":{\"cardInstanceId\":\"%s\",\"type\":\"SCAN\","
                        + "\"grade\":\"II\",\"category\":\"INFORMATION\"},\"playable\":%s}],\"kind\":\"ActionRound\"}}")
                .formatted(SCAN, playable);
    }

    private static String cards(int count) {
        var cards = new StringBuilder();
        for (var slot = 0; slot < count; slot++) {
            var id = slot == 0 ? SCAN : new UUID(0, 100 + slot);
            cards.append(slot == 0 ? "" : ",")
                    .append("{\"cardInstanceId\":\"%s\",\"cardType\":\"SCAN\",\"grade\":\"II\",\"dealSlot\":%d}"
                            .formatted(id, slot));
        }
        return cards.toString();
    }
}
