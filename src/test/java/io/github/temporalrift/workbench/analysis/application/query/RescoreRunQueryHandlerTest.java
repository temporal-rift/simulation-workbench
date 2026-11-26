package io.github.temporalrift.workbench.analysis.application.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.analysis.application.port.in.RescoreRunUseCase;
import io.github.temporalrift.workbench.analysis.domain.AnalysisResourceNotFoundException;
import io.github.temporalrift.workbench.analysis.domain.game.AnalyzedCase;
import io.github.temporalrift.workbench.analysis.domain.game.CaseOutcome;
import io.github.temporalrift.workbench.analysis.domain.game.GameEvent;
import io.github.temporalrift.workbench.analysis.domain.game.GameRecord;
import io.github.temporalrift.workbench.analysis.domain.port.out.RunSource;
import io.github.temporalrift.workbench.analysis.domain.port.out.VariantScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.BetOutcome;
import io.github.temporalrift.workbench.analysis.domain.scoring.FactionScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.InvalidScoringModelException;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.shared.execution.CaseState;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;

class RescoreRunQueryHandlerTest {

    private static final UUID RUN = new UUID(0, 1);
    private static final List<Faction> TABLE = List.of(Faction.WEAVERS, Faction.ERASERS, Faction.PROPHETS);
    private static final Map<String, Integer> DELTAS = Map.of("CHAIN_LINK_ADDED", 3, "CHAIN_LINK_MISSED", -1);

    private final List<AnalyzedCase> cases = new ArrayList<>();
    private final Map<UUID, GameRecord> games = new java.util.HashMap<>();
    private final RunSource source = new RunSource() {
        @Override
        public Optional<UUID> experimentOf(UUID runId) {
            return RUN.equals(runId) ? Optional.of(new UUID(9, 9)) : Optional.empty();
        }

        @Override
        public List<AnalyzedCase> cases(UUID runId) {
            return List.copyOf(cases);
        }

        @Override
        public Optional<GameRecord> countingGame(UUID runId, AnalyzedCase analyzedCase) {
            return Optional.ofNullable(games.get(analyzedCase.caseId()));
        }
    };
    private final VariantScoring scoring = (experimentId, variant) ->
            "v".equals(variant) ? Optional.of(new VariantScoring.Rules(DELTAS, 5)) : Optional.empty();
    private final RescoreRunQueryHandler handler = new RescoreRunQueryHandler(source, scoring);

    private static ScoringEngine engine(int threshold) {
        return new ScoringEngine(
                threshold,
                5,
                List.of(
                        new FactionScoring(Faction.WEAVERS, List.of(2, 4, 6), -1, Set.of(BetOutcome.MISSED), null, 0),
                        new FactionScoring(Faction.ERASERS, List.of(1), 0, Set.of(), null, 0),
                        new FactionScoring(Faction.PROPHETS, List.of(1), 0, Set.of(), null, 0)));
    }

    private void add(String seed, CaseState state, int eras, EndReason endReason) {
        var id = UUID.randomUUID();
        var outcome = state == CaseState.SUCCEEDED
                ? new CaseOutcome(endReason, Set.of(0), List.of(0, 0, 0), eras, 0, 0)
                : null;
        cases.add(new AnalyzedCase(id, "v", "t", seed, 3, List.of(), TABLE, state, outcome));
        if (state == CaseState.SUCCEEDED) {
            games.put(id, weaverGame(eras));
        }
    }

    /** The Weaver wins its link in every recorded era. */
    private static GameRecord weaverGame(int eras) {
        var events = new ArrayList<GameEvent>();
        for (var era = 1; era <= eras; era++) {
            events.add(new GameEvent.SpecialPlayed(0, era, SpecialAction.THREAD));
            events.add(new GameEvent.ScoreRecorded(0, era, "CHAIN_LINK_ADDED", 3));
        }
        return new GameRecord(events, List.of(), List.of());
    }

    private RescoreRunUseCase.Query query(ScoringEngine engine) {
        return new RescoreRunUseCase.Query(RUN, engine, "v", "t");
    }

    @Test
    void judgesTheCasesTheRaceCanEndInAndCountsTheTruncatedOnes() {
        add("1", CaseState.SUCCEEDED, 5, EndReason.TIMELINE_STABILIZED);
        add("2", CaseState.SUCCEEDED, 5, EndReason.WIN_CONDITION_MET);
        add("3", CaseState.SUCCEEDED, 3, EndReason.WIN_CONDITION_MET);

        var result = handler.handle(query(engine(500)));

        assertThat(result.complete()).isTrue();
        assertThat(result.playerCounts()).singleElement().satisfies(counts -> {
            assertThat(counts.judgedCases()).isEqualTo(2);
            assertThat(counts.truncatedCases()).isEqualTo(1);
            assertThat(counts.meanEras()).isEqualTo(5.0);
        });
        assertThat(result.cells()).hasSize(3);
        assertThat(result.cells().stream().filter(cell -> cell.faction() == Faction.WEAVERS))
                .singleElement()
                .satisfies(cell -> assertThat(cell.ratio()).isEqualTo(3.0));
    }

    @Test
    void anUnfinishedCaseMakesTheRescoringIncompleteAndIsNotUsed() {
        add("1", CaseState.SUCCEEDED, 5, EndReason.TIMELINE_STABILIZED);
        add("2", CaseState.RUNNING, 0, null);

        var result = handler.handle(query(engine(500)));

        assertThat(result.complete()).isFalse();
        assertThat(result.playerCounts())
                .singleElement()
                .satisfies(counts -> assertThat(counts.judgedCases()).isEqualTo(1));
    }

    @Test
    void aPlayerCountWhoseCasesAreAllTruncatedHasNoCellsAndNoMean() {
        add("1", CaseState.SUCCEEDED, 3, EndReason.WIN_CONDITION_MET);

        var result = handler.handle(query(engine(500)));

        assertThat(result.cells()).isEmpty();
        assertThat(result.playerCounts()).singleElement().satisfies(counts -> {
            assertThat(counts.judgedCases()).isZero();
            assertThat(counts.truncatedCases()).isEqualTo(1);
            assertThat(counts.meanEras()).isNull();
        });
    }

    @Test
    void equalRequestsGiveEqualResults() {
        for (var seed = 1; seed <= 6; seed++) {
            add("" + seed, CaseState.SUCCEEDED, 5, EndReason.TIMELINE_STABILIZED);
        }

        assertThat(handler.handle(query(engine(9)))).isEqualTo(handler.handle(query(engine(9))));
    }

    @Test
    void anUnknownRunVariantOrTableIsNotFound() {
        add("1", CaseState.SUCCEEDED, 5, EndReason.TIMELINE_STABILIZED);

        var unknownRun = new RescoreRunUseCase.Query(new UUID(5, 5), engine(10), "v", "t");
        var unknownVariant = new RescoreRunUseCase.Query(RUN, engine(10), "other", "t");
        var unknownTable = new RescoreRunUseCase.Query(RUN, engine(10), "v", "other");

        assertThatThrownBy(() -> handler.handle(unknownRun)).isInstanceOf(AnalysisResourceNotFoundException.class);
        assertThatThrownBy(() -> handler.handle(unknownVariant)).isInstanceOf(AnalysisResourceNotFoundException.class);
        assertThatThrownBy(() -> handler.handle(unknownTable)).isInstanceOf(AnalysisResourceNotFoundException.class);
    }

    @Test
    void anEngineWithoutASeatedFactionIsRefusedNamingTheFactions() {
        add("1", CaseState.SUCCEEDED, 5, EndReason.TIMELINE_STABILIZED);
        var weaversOnly = new ScoringEngine(
                10, 5, List.of(new FactionScoring(Faction.WEAVERS, List.of(2), 0, Set.of(), null, 0)));

        assertThatThrownBy(() -> handler.handle(query(weaversOnly)))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("engine.factions"));
    }

    @Test
    void anInvalidEngineIsRefusedNamingTheValue() {
        add("1", CaseState.SUCCEEDED, 5, EndReason.TIMELINE_STABILIZED);

        assertThatThrownBy(() -> handler.handle(query(engine(0))))
                .isInstanceOfSatisfying(
                        InvalidScoringModelException.class,
                        e -> assertThat(e.field()).isEqualTo("engine.winThreshold"));
    }
}
