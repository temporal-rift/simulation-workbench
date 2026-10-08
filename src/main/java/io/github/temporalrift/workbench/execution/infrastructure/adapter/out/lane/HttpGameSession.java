package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ClockAdvance;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.GameResult;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.ScoringApi;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;

/**
 * A real game observed and driven through participant operations and the services' execution barriers.
 * The game is settled only when both services report a drained checkpoint for this case and every seat's
 * projection has stopped changing; accepted state read before that point is never treated as current.
 */
public class HttpGameSession implements GameSession {

    private static final String GAME_ENDED = "GAME_ENDED";
    private static final int CLOCK_RETRIES = 2;

    private final CaseLane.CaseContext context;
    private final UUID gameId;
    private final SimulationExecutionApi gameControl;
    private final SimulationExecutionApi timelineControl;
    private final HttpParticipantGateway gateway;
    private final ParticipantGateway participants;
    private final List<HttpParticipantGateway.Participant> seats;
    private final ScoringApi scoring;
    private final LaneEndpoints.Barrier barrier;
    private final Sleeper sleeper;

    private Map<Integer, Integer> lastRevisions = Map.of();
    private int stableReads;

    public HttpGameSession(
            CaseLane.CaseContext context,
            UUID gameId,
            SimulationExecutionApi gameControl,
            SimulationExecutionApi timelineControl,
            HttpParticipantGateway gateway,
            ParticipantGateway participants,
            List<HttpParticipantGateway.Participant> seats,
            ScoringApi scoring,
            LaneEndpoints.Barrier barrier,
            Sleeper sleeper) {
        this.context = context;
        this.gameId = gameId;
        this.gameControl = gameControl;
        this.timelineControl = timelineControl;
        this.gateway = gateway;
        this.participants = participants;
        this.seats = List.copyOf(seats);
        this.scoring = scoring;
        this.barrier = barrier;
        this.sleeper = sleeper;
    }

    @Override
    public UUID gameId() {
        return gameId;
    }

    @Override
    public ParticipantGateway participants() {
        return participants;
    }

    /** Whether the services and projection have settled; the gateway uses it to judge accepted state current. */
    public boolean isSettled() {
        var game = checkpoint(gameControl, "game-service");
        var timeline = checkpoint(timelineControl, "timeline-service");
        var drained = Boolean.TRUE.equals(game.getDrained()) && Boolean.TRUE.equals(timeline.getDrained());
        var revisions = new HashMap<Integer, Integer>();
        var complete = true;
        for (var seat : seats) {
            var revision = gateway.stateOf(seat.seatIndex()).getRevision();
            complete &= revision != null;
            revisions.put(seat.seatIndex(), revision);
        }
        stableReads = complete && revisions.equals(lastRevisions) ? stableReads + 1 : 0;
        lastRevisions = revisions;
        return drained && stableReads >= barrier.stablePolls();
    }

    @Override
    public GameProgress poll() {
        if (!awaitSettled()) {
            return new GameProgress.Waiting();
        }
        var pending = new ArrayList<Integer>();
        for (var seat : seats) {
            var state = gateway.stateOf(seat.seatIndex());
            if (GAME_ENDED.equals(state.getPhase().name())) {
                return new GameProgress.Ended();
            }
            if (ObservationMapper.owed(seat.seatIndex(), seat.playerId(), state).isPresent()) {
                pending.add(seat.seatIndex());
            }
        }
        return pending.isEmpty() ? new GameProgress.Waiting() : new GameProgress.Open(pending);
    }

    @Override
    public void advanceClock() {
        var game = checkpoint(gameControl, "game-service");
        var timeline = checkpoint(timelineControl, "timeline-service");
        var target = earliest(game.getNextDeadline(), timeline.getNextDeadline());
        if (target == null) {
            sleeper.sleep(barrier.pollInterval());
            return;
        }
        advance(gameControl, "game-service", target);
        advance(timelineControl, "timeline-service", target);
    }

    @Override
    public Optional<AuthoritativeEnding> ending() {
        if (!awaitSettled()) {
            return Optional.empty();
        }
        var states = new ArrayList<PlayerGameStateResponse>();
        for (var seat : seats) {
            var state = gateway.stateOf(seat.seatIndex());
            if (!GAME_ENDED.equals(state.getPhase().name()) || state.getResult() == null) {
                return Optional.empty();
            }
            states.add(state);
        }
        var result = states.getFirst().getResult();
        if (states.stream().anyMatch(state -> !sameResult(result, state.getResult()))) {
            return Optional.empty();
        }
        var scores = scores();
        var finalScores = finalScores(result);
        if (scores.isEmpty() || !scores.get().equals(scoresByPlayer(result))) {
            return Optional.empty();
        }
        var eras = states.stream()
                .mapToInt(PlayerGameStateResponse::getEraNumber)
                .max()
                .orElse(0);
        return Optional.of(new AuthoritativeEnding(
                EndReason.valueOf(result.getEndReason().name()), winners(result), finalScores, eras));
    }

    /** Waits a bounded number of checks for the services and projection to settle. */
    public boolean awaitSettled() {
        for (var poll = 0; poll < barrier.maxPolls(); poll++) {
            if (isSettled()) {
                return true;
            }
            sleeper.sleep(barrier.pollInterval());
        }
        return false;
    }

    private Optional<Map<UUID, Integer>> scores() {
        try {
            var body = scoring.getScores(gameId).getBody();
            if (body == null) {
                return Optional.empty();
            }
            var scores = new HashMap<UUID, Integer>();
            body.getScores().forEach(score -> scores.put(score.getPlayerId(), score.getScore()));
            return Optional.of(scores);
        } catch (RestClientException e) {
            return Optional.empty();
        }
    }

    private static Map<UUID, Integer> scoresByPlayer(GameResult result) {
        var scores = new HashMap<UUID, Integer>();
        result.getFinalScores().forEach(score -> scores.put(score.getPlayerId(), score.getScore()));
        return scores;
    }

    private List<FinalScore> finalScores(GameResult result) {
        return result.getFinalScores().stream()
                .map(score ->
                        new FinalScore(seatOf(score.getPlayerId()), factionOf(score.getPlayerId()), score.getScore()))
                .sorted(Comparator.comparingInt(FinalScore::seatIndex))
                .toList();
    }

    private List<Winner> winners(GameResult result) {
        return result.getWinners().stream()
                .map(winner -> {
                    var seat = seatOf(winner.getPlayerId());
                    var planned = factionOf(winner.getPlayerId());
                    if (winner.getFaction() != null
                            && !winner.getFaction().name().equals(planned)) {
                        throw new AttemptFailedException(
                                FailureCode.CONFIGURATION_DRIFT,
                                "Seat " + seat + " was revealed as "
                                        + winner.getFaction().name() + " but planned as " + planned);
                    }
                    return new Winner(
                            seat,
                            planned,
                            winner.getWinType() == null
                                    ? null
                                    : WinType.valueOf(winner.getWinType().name()));
                })
                .sorted(Comparator.comparingInt(Winner::seatIndex))
                .toList();
    }

    private static boolean sameResult(GameResult a, GameResult b) {
        return a.getEndReason() == b.getEndReason()
                && Set.copyOf(a.getWinners().stream().map(w -> w.getPlayerId()).toList())
                        .equals(Set.copyOf(b.getWinners().stream()
                                .map(w -> w.getPlayerId())
                                .toList()))
                && scoresByPlayer(a).equals(scoresByPlayer(b));
    }

    private int seatOf(UUID playerId) {
        return seats.stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new AttemptFailedException(
                        FailureCode.CONTRACT_MISMATCH, "The result names an unknown player " + playerId))
                .seatIndex();
    }

    private String factionOf(UUID playerId) {
        return context.seats().get(seatOf(playerId)).faction();
    }

    private ExecutionCheckpoint checkpoint(SimulationExecutionApi control, String service) {
        try {
            var checkpoint = control.getSimulationCheckpoint().getBody();
            if (checkpoint == null) {
                throw new AttemptFailedException(
                        FailureCode.CONTRACT_MISMATCH, service + " returned no execution checkpoint");
            }
            if (!checkpoint.getCaseKey().equals(context.caseKey())
                    || !checkpoint.getManifestDigest().equals(context.manifestDigest())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT,
                        service + " executes a different case or manifest than the attempt configured");
            }
            return checkpoint;
        } catch (RestClientException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "The " + service + " checkpoint could not be read: " + e.getMessage(),
                    e);
        }
    }

    private void advance(SimulationExecutionApi control, String service, OffsetDateTime target) {
        for (var retry = 0; retry <= CLOCK_RETRIES; retry++) {
            var current = checkpoint(control, service);
            if (!current.getLogicalTime().isBefore(target)) {
                return;
            }
            var operationId = UUID.nameUUIDFromBytes(
                    (context.caseKey() + "|" + service + "|" + current.getRevision() + "|" + target)
                            .getBytes(StandardCharsets.UTF_8));
            try {
                control.advanceSimulationClock(new ClockAdvance()
                        .operationId(operationId)
                        .expectedRevision(current.getRevision())
                        .targetTime(target));
                return;
            } catch (RestClientResponseException e) {
                if (!"STALE_EXECUTION_REVISION".equals(ProblemCodes.codeOf(e)) || retry == CLOCK_RETRIES) {
                    throw new AttemptFailedException(
                            FailureCode.EXECUTION_FAILED,
                            service + " rejected the clock advance: " + ProblemCodes.codeOf(e),
                            e);
                }
            } catch (RestClientException e) {
                throw new AttemptFailedException(
                        FailureCode.EXECUTION_FAILED,
                        "The " + service + " clock could not be advanced: " + e.getMessage(),
                        e);
            }
        }
    }

    private static OffsetDateTime earliest(OffsetDateTime first, OffsetDateTime second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return first.isBefore(second) ? first : second;
    }
}
