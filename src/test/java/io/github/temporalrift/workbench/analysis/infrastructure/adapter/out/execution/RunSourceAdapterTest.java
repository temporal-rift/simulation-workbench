package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.execution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.execution.CaseEvidence;
import io.github.temporalrift.workbench.execution.RunCase;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunSummary;
import io.github.temporalrift.workbench.execution.domain.evidence.ObservedEvent;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.HandCard;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.CardGrade;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.StoredJson;

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
                    new RunCase.Seat(0, Faction.ERASERS, "random", "1.0.0"),
                    new RunCase.Seat(1, Faction.WEAVERS, "random", "1.0.0"),
                    new RunCase.Seat(2, Faction.PROPHETS, "random", "1.0.0")),
            CaseState.SUCCEEDED,
            new RunCase.Result(
                    EndReason.WIN_CONDITION_MET,
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
                step(play(SCAN), StepOutcome.ACCEPTED, actionRound(true)),
                step(thread(), StepOutcome.REJECTED, actionRound(true)),
                step(thread(), StepOutcome.NOT_SPENT, actionRound(true)));

        var gameRecord = adapter(new CaseEvidence(steps, events))
                .countingGame(RUN, adapter(null).cases(RUN).getFirst())
                .orElseThrow();

        assertThat(gameRecord.events())
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
        assertThat(gameRecord.observations())
                .containsExactly(new GameRecord.RoundObservation(
                        1, 1, 1, List.of(new GameRecord.HeldCard(SCAN, SCAN_II, true))));
        assertThat(gameRecord.submissions())
                .containsExactly(new GameRecord.SpecialSubmission(1, SpecialAction.THREAD, true));
    }

    @Test
    void aStepOutsideAnActionRoundObservesNoRound() {
        var gameRecord = adapter(new CaseEvidence(
                        List.of(step(
                                new Candidate.Decline(),
                                StepOutcome.ACCEPTED,
                                new DecisionWindow.Declaration(1, List.of()))),
                        List.of()))
                .countingGame(RUN, adapter(null).cases(RUN).getFirst())
                .orElseThrow();

        assertThat(gameRecord.observations()).isEmpty();
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
                new StoredJson(JsonMapper.builder().build()));
    }

    private static ObservedEvent event(String type, String payload) {
        return new ObservedEvent("game.events", 0, 0, UUID.randomUUID(), type, null, null, GAME, null, 1, payload);
    }

    private static StepRecord step(Candidate decision, StepOutcome outcome, DecisionWindow window) {
        var observation = new EntitledObservation(1, Faction.WEAVERS, List.of(), List.of(), window);
        return new StepRecord(0, 1, window.ref(), Instant.EPOCH, observation, decision, outcome, null, null);
    }

    private static Candidate play(UUID card) {
        return new Candidate.PlayCard(card, new Target.Disguise(CardCategory.INFORMATION));
    }

    private static Candidate thread() {
        return new Candidate.PlaySpecial(SpecialAction.THREAD, new Target.Player(WEAVER));
    }

    private static DecisionWindow actionRound(boolean playable) {
        var scan = new DealtCard(SCAN, CardType.SCAN, CardGrade.II, CardCategory.INFORMATION);
        return new DecisionWindow.ActionRound(1, 1, List.of(new HandCard(scan, playable)), List.of(), List.of());
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
