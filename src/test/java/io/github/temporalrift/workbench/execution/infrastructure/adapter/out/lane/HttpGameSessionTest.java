package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.domain.run.SeatPlan;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.action.ActionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ClockAcknowledgement;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ClockAdvance;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionState;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.ProjectionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Faction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.FinalScore;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.GameResult;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.GameWinner;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Phase;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.ScoringApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.model.PlayerScore;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.model.ScoresResponse;
import io.github.temporalrift.workbench.execution.support.StubEventObserver;

class HttpGameSessionTest {

    private static final UUID CASE_KEY = new UUID(5, 5);
    private static final String DIGEST = "a".repeat(64);
    private static final OffsetDateTime EPOCH = OffsetDateTime.parse("2026-01-01T00:00:00Z");
    private static final UUID GAME = ProjectionStates.GAME;
    private static final List<String> FACTIONS = List.of("ERASERS", "PROPHETS", "WEAVERS");

    private final Map<Integer, ProjectionApi> projections = new HashMap<>();
    private final Map<Integer, PlayerGameStateResponse> states = new HashMap<>();
    private final SimulationExecutionApi gameControl = mock(SimulationExecutionApi.class);
    private final SimulationExecutionApi timelineControl = mock(SimulationExecutionApi.class);
    private final ScoringApi scoring = mock(ScoringApi.class);
    private final AtomicInteger sleeps = new AtomicInteger();
    private final StubEventObserver events = new StubEventObserver();
    private HttpGameSession session;

    @BeforeEach
    void setUp() {
        var seats = new ArrayList<HttpParticipantGateway.Participant>();
        var plans = new ArrayList<SeatPlan>();
        for (var seat = 0; seat < 3; seat++) {
            var projection = mock(ProjectionApi.class);
            projections.put(seat, projection);
            seats.add(new HttpParticipantGateway.Participant(seat, player(seat), projection, mock(ActionApi.class)));
            plans.add(new SeatPlan(seat, FACTIONS.get(seat), "random", "1.0.0"));
            setState(seat, handSelection());
        }
        var gateway = new HttpParticipantGateway(GAME, seats, () -> true);
        var context = new CaseLane.CaseContext(
                new UUID(1, 1), new UUID(2, 2), CASE_KEY, "42", DIGEST, plans, Instant.parse("2026-01-01T00:00:00Z"));
        session = new HttpGameSession(
                context,
                new HttpGameSession.ServiceControls(gameControl, timelineControl),
                gateway,
                gateway,
                scoring,
                new HttpGameSession.Pacing(
                        new LaneEndpoints.Barrier(Duration.ZERO, 2, 3), _ -> sleeps.incrementAndGet()),
                events);
        checkpoints(true, null);
    }

    @Test
    void aSettledGameReportsTheSeatsThatStillOweADecision() {
        var progress = session.poll();

        assertThat(progress).isEqualTo(new GameProgress.Open(List.of(0, 1, 2)));
    }

    @Test
    void acceptedStateIsNeverReadAsCurrentWhileTheServicesAreStillWorking() {
        checkpoints(false, null);

        var progress = session.poll();

        assertThat(progress).isInstanceOf(GameProgress.Waiting.class);
        assertThat(sleeps.get()).isEqualTo(3);
    }

    @Test
    void aProjectionThatKeepsChangingIsNotSettled() {
        var revision = new AtomicInteger();
        for (var seat = 0; seat < 3; seat++) {
            var current = seat;
            when(projections.get(seat).getGameState(GAME)).thenAnswer(_ -> {
                var state = handSelection();
                state.setRevision(revision.incrementAndGet());
                return ResponseEntity.ok(state);
            });
            states.remove(current);
        }

        assertThat(session.poll()).isInstanceOf(GameProgress.Waiting.class);
    }

    @Test
    void aSeatWithNothingOwedMeansTheGameIsWaitingOnTheServices() {
        for (var seat = 0; seat < 3; seat++) {
            setState(seat, ProjectionStates.base(Phase.RESOLUTION, 1));
        }

        assertThat(session.poll()).isInstanceOf(GameProgress.Waiting.class);
    }

    @Test
    void anEndedGameIsReported() {
        for (var seat = 0; seat < 3; seat++) {
            setState(seat, ProjectionStates.base(Phase.GAME_ENDED, 3));
        }

        assertThat(session.poll()).isInstanceOf(GameProgress.Ended.class);
    }

    @Test
    void aCheckpointForAnotherCaseOrManifestIsConfigurationDrift() {
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(new UUID(9, 9), DIGEST, true, null, 0)));

        assertThatThrownBy(session::poll)
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT));

        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, "b".repeat(64), true, null, 0)));
        assertThatThrownBy(session::poll)
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT));
    }

    @Test
    void theClockAdvancesBothServicesToTheEarliestDeadline() {
        var gameDeadline = EPOCH.plusSeconds(90);
        var timelineDeadline = EPOCH.plusSeconds(30);
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, gameDeadline, 4)));
        when(timelineControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, timelineDeadline, 7)));
        when(gameControl.advanceSimulationClock(any())).thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));
        when(timelineControl.advanceSimulationClock(any())).thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));

        session.advanceClock();

        var game = ArgumentCaptor.forClass(ClockAdvance.class);
        var timeline = ArgumentCaptor.forClass(ClockAdvance.class);
        verify(gameControl).advanceSimulationClock(game.capture());
        verify(timelineControl).advanceSimulationClock(timeline.capture());
        assertThat(game.getValue().getTargetTime()).isEqualTo(timelineDeadline);
        assertThat(game.getValue().getExpectedRevision()).isEqualTo(4L);
        assertThat(timeline.getValue().getTargetTime()).isEqualTo(timelineDeadline);
        assertThat(timeline.getValue().getExpectedRevision()).isEqualTo(7L);
        assertThat(game.getValue().getOperationId())
                .isNotEqualTo(timeline.getValue().getOperationId());
    }

    @Test
    void repeatingAnAdvanceReusesItsOperationIdentitySoItIsIdempotent() {
        var deadline = EPOCH.plusSeconds(30);
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, deadline, 4)));
        when(timelineControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, deadline, 4)));
        when(gameControl.advanceSimulationClock(any())).thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));
        when(timelineControl.advanceSimulationClock(any())).thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));

        session.advanceClock();
        session.advanceClock();

        var game = ArgumentCaptor.forClass(ClockAdvance.class);
        verify(gameControl, times(2)).advanceSimulationClock(game.capture());
        assertThat(game.getAllValues().get(0).getOperationId())
                .isEqualTo(game.getAllValues().get(1).getOperationId());
    }

    @Test
    void aServiceAlreadyAtTheTargetIsNotAdvancedAgain() {
        var deadline = EPOCH.plusSeconds(30);
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(
                        checkpoint(CASE_KEY, DIGEST, true, deadline, 4).logicalTime(deadline)));
        when(timelineControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, deadline, 4)));
        when(timelineControl.advanceSimulationClock(any())).thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));

        session.advanceClock();

        verify(gameControl, never()).advanceSimulationClock(any());
        verify(timelineControl).advanceSimulationClock(any());
    }

    @Test
    void aStaleRevisionIsReReadAndRetriedButARegressionFailsTheAttempt() {
        var deadline = EPOCH.plusSeconds(30);
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, deadline, 4)));
        when(timelineControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, true, deadline, 4)));
        when(gameControl.advanceSimulationClock(any()))
                .thenThrow(problem(HttpStatus.CONFLICT, "{\"code\":\"STALE_EXECUTION_REVISION\"}"))
                .thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));
        when(timelineControl.advanceSimulationClock(any())).thenReturn(ResponseEntity.ok(new ClockAcknowledgement()));

        session.advanceClock();

        verify(gameControl, times(2)).advanceSimulationClock(any());

        when(gameControl.advanceSimulationClock(any()))
                .thenThrow(problem(HttpStatus.UNPROCESSABLE_CONTENT, "{\"code\":\"CLOCK_REGRESSION\"}"));
        assertThatThrownBy(session::advanceClock)
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().message()).contains("CLOCK_REGRESSION"));
    }

    @Test
    void withNoDeadlineThereIsNothingToAdvanceAndTheSessionJustWaits() {
        session.advanceClock();

        verify(gameControl, never()).advanceSimulationClock(any());
        verify(timelineControl, never()).advanceSimulationClock(any());
        assertThat(sleeps.get()).isEqualTo(1);
    }

    @Test
    void aReconciledEndingCarriesWinnersScoresAndErasBySeat() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        var ending = session.ending().orElseThrow();

        assertThat(ending.endReason()).isEqualTo(EndReason.WIN_CONDITION_MET);
        assertThat(ending.eras()).isEqualTo(3);
        assertThat(ending.winners()).singleElement().satisfies(winner -> {
            assertThat(winner.seatIndex()).isZero();
            assertThat(winner.faction()).isEqualTo("ERASERS");
            assertThat(winner.winType()).isEqualTo(WinType.SCORE_THRESHOLD);
        });
        assertThat(ending.finalScores())
                .extracting(score -> score.seatIndex() + ":" + score.faction() + ":" + score.score())
                .containsExactly("0:ERASERS:30", "1:PROPHETS:20", "2:WEAVERS:10");
    }

    @Test
    void anAbandonedGameEndsWithoutWinnersAndStillHasItsScores() {
        endGame(GameResult.EndReasonEnum.ALL_PLAYERS_ABANDONED, List.of(), 2, 30, 20, 10);
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        var ending = session.ending().orElseThrow();

        assertThat(ending.endReason()).isEqualTo(EndReason.ALL_PLAYERS_ABANDONED);
        assertThat(ending.winners()).isEmpty();
        assertThat(ending.finalScores()).hasSize(3);
    }

    @Test
    void specialEndingWinnersHaveNoWinType() {
        endGame(GameResult.EndReasonEnum.TIMELINE_COLLAPSED, List.of(1, 2), 5, 30, 20, 20);
        states.values().forEach(state -> state.getResult().getWinners().forEach(w -> w.setWinType(null)));
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 20)));

        var ending = session.ending().orElseThrow();

        assertThat(ending.winners()).hasSize(2);
        assertThat(ending.winners())
                .allSatisfy(winner -> assertThat(winner.winType()).isNull());
    }

    @Test
    void scoreEvidenceThatIsMissingOrDisagreesKeepsTheCaseUnreconciled() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);

        when(scoring.getScores(GAME)).thenThrow(new ResourceAccessException("down"));
        assertThat(session.ending()).isEmpty();

        doReturn(ResponseEntity.ok(scores(30, 20, 9))).when(scoring).getScores(GAME);
        assertThat(session.ending()).isEmpty();
    }

    @Test
    void aGameEndedFactThatHasNotBeenObservedYetKeepsTheCaseIncompleteInsteadOfScoringZero() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        events.gameEnded(null);
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        assertThat(session.ending()).isEmpty();

        published("WIN_CONDITION_MET", 30, 20, 10);
        assertThat(session.ending()).isPresent();
    }

    @Test
    void aGameEndedFactThatDisagreesWithTheParticipantResultKeepsTheEndingOpen() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        published("WIN_CONDITION_MET", 30, 20, 0);
        assertThat(session.ending()).isEmpty();

        published("TIMELINE_COLLAPSED", 30, 20, 10);
        assertThat(session.ending()).isEmpty();
    }

    @Test
    void anObserverThatHasNotReachedTheEndOfItsSourcesKeepsTheGameUnsettled() {
        events.caughtUp(false);

        assertThat(session.isSettled()).isFalse();
        assertThat(session.poll()).isInstanceOf(GameProgress.Waiting.class);

        events.caughtUp(true);
        assertThat(session.awaitSettled()).isTrue();
    }

    @Test
    void theLogicalTimeComesFromTheGameServiceCheckpoint() {
        assertThat(session.logicalTime()).isEqualTo(EPOCH.toInstant());
    }

    @Test
    void resultsThatDifferBetweenSeatsAreNotAuthoritativeYet() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        states.get(2).getResult().setWinners(List.of(winner(1)));
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        assertThat(session.ending()).isEmpty();
    }

    @Test
    void seatsThatDisagreeOnAWinnersTypeAreNotAuthoritativeYet() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        states.get(1).getResult().getWinners().getFirst().setWinType(GameWinner.WinTypeEnum.FACTION_OBJECTIVE);
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        assertThat(session.ending()).isEmpty();
    }

    @Test
    void aSeatThatHasNotSeenTheEndYetKeepsTheEndingOpen() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        setState(1, ProjectionStates.base(Phase.RESOLUTION, 3));
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        assertThat(session.ending()).isEmpty();
    }

    @Test
    void aWinnerRevealedAsAnotherFactionThanPlannedIsConfigurationDrift() {
        endGame(GameResult.EndReasonEnum.WIN_CONDITION_MET, List.of(0), 3, 30, 20, 10);
        states.values()
                .forEach(state -> state.getResult().getWinners().getFirst().setFaction(Faction.WEAVERS));
        when(scoring.getScores(GAME)).thenReturn(ResponseEntity.ok(scores(30, 20, 10)));

        assertThatThrownBy(session::ending)
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT));
    }

    private void endGame(
            GameResult.EndReasonEnum reason, List<Integer> winners, int era, int first, int second, int third) {
        for (var seat = 0; seat < 3; seat++) {
            var state = ProjectionStates.base(Phase.GAME_ENDED, era);
            state.setResult(new GameResult(
                    reason,
                    winners.stream().map(HttpGameSessionTest::winner).toList(),
                    List.of(
                            new FinalScore(player(0), first),
                            new FinalScore(player(1), second),
                            new FinalScore(player(2), third)),
                    GameResult.RevealBoundaryEnum.FACTIONS_AND_SCORES_PUBLIC));
            setState(seat, state);
        }
        published(reason.name(), first, second, third);
    }

    /** The GameEnded fact the observer retained from the broker. */
    private void published(String reason, int first, int second, int third) {
        events.gameEnded(
                new GameEventObserver.GameEnded(reason, Map.of(player(0), first, player(1), second, player(2), third)));
    }

    private static GameWinner winner(int seat) {
        var winner = new GameWinner(player(seat));
        winner.setFaction(Faction.valueOf(FACTIONS.get(seat)));
        winner.setWinType(GameWinner.WinTypeEnum.SCORE_THRESHOLD);
        return winner;
    }

    private static ScoresResponse scores(int first, int second, int third) {
        return new ScoresResponse(
                GAME,
                3,
                List.of(
                        new PlayerScore(player(0), "bot-0", first),
                        new PlayerScore(player(1), "bot-1", second),
                        new PlayerScore(player(2), "bot-2", third)));
    }

    private void checkpoints(boolean drained, OffsetDateTime nextDeadline) {
        when(gameControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, drained, nextDeadline, 0)));
        when(timelineControl.getSimulationCheckpoint())
                .thenReturn(ResponseEntity.ok(checkpoint(CASE_KEY, DIGEST, drained, nextDeadline, 0)));
    }

    private static ExecutionCheckpoint checkpoint(
            UUID caseKey, String digest, boolean drained, OffsetDateTime nextDeadline, long revision) {
        return new ExecutionCheckpoint()
                .caseKey(caseKey)
                .manifestDigest(digest)
                .revision(revision)
                .logicalTime(EPOCH)
                .gameId(GAME)
                .state(ExecutionState.ACTIVE)
                .drained(drained)
                .outboxPending(0)
                .continuationsPending(0)
                .dueTimersPending(0)
                .nextDeadline(nextDeadline);
    }

    private void setState(int seat, PlayerGameStateResponse state) {
        states.put(seat, state);
        when(projections.get(seat).getGameState(GAME)).thenReturn(ResponseEntity.ok(state));
    }

    private static PlayerGameStateResponse handSelection() {
        return ProjectionStates.handSelection(1);
    }

    private static UUID player(int seat) {
        return ProjectionStates.id(seat + 1L);
    }

    private static HttpClientErrorException problem(HttpStatus status, String body) {
        return HttpClientErrorException.create(
                status,
                status.getReasonPhrase(),
                new HttpHeaders(),
                body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
    }
}
