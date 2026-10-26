package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameEventObserver;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.SimulationExecutionApi;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ClockAdvance;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.control.model.ExecutionCheckpoint;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.GameResult;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Phase;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PlayerGameStateResponse;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.scoring.ScoringApi;
import io.github.temporalrift.workbench.policy.domain.port.out.ParticipantGateway;
import io.github.temporalrift.workbench.shared.execution.FailureCode;
import io.github.temporalrift.workbench.shared.game.EndReason;
import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * A real game observed and driven through participant operations and the services' execution barriers.
 * The game is settled only when both services report a drained checkpoint for this case, every consumer of the
 * published records has applied the end offsets the event observer read, and the seats' states were read after that;
 * accepted state read before that point is never treated as current.
 */
public class HttpGameSession implements GameSession {

    private static final int CLOCK_RETRIES = 2;

    private static final String GAME_SERVICE = LaneService.GAME.serviceName();
    private static final String TIMELINE_SERVICE = LaneService.TIMELINE.serviceName();

    private final CaseLane.CaseContext context;
    private final SimulationExecutionApi gameControl;
    private final SimulationExecutionApi timelineControl;
    private final HttpParticipantGateway gateway;
    private final ParticipantGateway participants;
    private final List<HttpParticipantGateway.Participant> seats;
    private final ScoringApi scoring;
    private final LaneEndpoints.Barrier barrier;
    private final Sleeper sleeper;
    private final LaneWaits waits;
    private final GameEventObserver events;

    /** What the last settle wait read; the decisions of the same iteration reuse it instead of reading again. */
    private Settled settled;
    /** The game service's logical time as last read; it moves only when this session advances the clock. */
    private Instant knownLogicalTime;

    /** The execution controls of the two services that host a game. */
    public record ServiceControls(SimulationExecutionApi game, SimulationExecutionApi timeline) {}

    /** How the session waits for the services and projection to settle. */
    public record Pacing(LaneEndpoints.Barrier barrier, Sleeper sleeper, LaneWaits waits) {}

    /**
     * @param gateway the raw participant operations of the game, which also name its id and seats
     * @param participants the same operations made durable through the command ledger
     */
    public HttpGameSession(
            CaseLane.CaseContext context,
            ServiceControls controls,
            HttpParticipantGateway gateway,
            ParticipantGateway participants,
            ScoringApi scoring,
            Pacing pacing,
            GameEventObserver events) {
        this.context = context;
        this.gameControl = controls.game();
        this.timelineControl = controls.timeline();
        this.gateway = gateway;
        this.participants = participants;
        this.seats = gateway.seats();
        this.scoring = scoring;
        this.barrier = pacing.barrier();
        this.sleeper = pacing.sleeper();
        this.waits = pacing.waits();
        this.events = events;
    }

    @Override
    public UUID gameId() {
        return gateway.gameId();
    }

    @Override
    public ParticipantGateway participants() {
        return participants;
    }

    /** Whether the services and projection have settled; the gateway uses it to judge accepted state current. */
    public boolean isSettled() {
        settled = null;
        var checkpoints = readCheckpoints();
        return checkpoints.drained() && projectionSettled(checkpoints).settled();
    }

    /** Both services' checkpoints for the case. */
    private record Checkpoints(ExecutionCheckpoint game, ExecutionCheckpoint timeline) {

        /** Whether both services report their work for the case drained. */
        boolean drained() {
            return Boolean.TRUE.equals(game.getDrained()) && Boolean.TRUE.equals(timeline.getDrained());
        }
    }

    /** A settled game: the checkpoints and every seat's state (in seat order) read by the settling check. */
    private record Settled(Checkpoints checkpoints, List<PlayerGameStateResponse> states) {}

    /** One projection check: whether it is settled, and the states it read. */
    private record Projection(boolean settled, List<PlayerGameStateResponse> states) {}

    private Checkpoints readCheckpoints() {
        return new Checkpoints(checkpoint(gameControl, GAME_SERVICE), checkpoint(timelineControl, TIMELINE_SERVICE));
    }

    /** Whether every consumer has applied what the observer read, and then every seat's state as projected. */
    private Projection projectionSettled(Checkpoints checkpoints) {
        if (!events.drain() || !consumersApplied(checkpoints)) {
            return new Projection(false, List.of());
        }
        var states = new ArrayList<PlayerGameStateResponse>();
        for (var seat : seats) {
            var projected = gateway.stateOnceProjected(seat.seatIndex());
            if (projected.isEmpty()) {
                return new Projection(false, List.of());
            }
            states.add(projected.get());
        }
        return new Projection(true, states);
    }

    /** Whether every consumer group has committed past the end offsets the observer just read. */
    private boolean consumersApplied(Checkpoints checkpoints) {
        var ends = events.endOffsets();
        return reached(ends, checkpoints.game()) && reached(ends, checkpoints.timeline()) && events.readSideApplied();
    }

    private static boolean reached(Map<EvidenceLedger.SourcePartition, Long> ends, ExecutionCheckpoint checkpoint) {
        return checkpoint.getSourceWatermarks().stream().allMatch(watermark -> {
            var end = ends.get(new EvidenceLedger.SourcePartition(watermark.getTopic(), watermark.getPartition()));
            return end == null || watermark.getNextOffset() >= end;
        });
    }

    @Override
    public GameProgress poll() {
        var snapshot = settle();
        if (snapshot.isEmpty()) {
            return new GameProgress.Waiting();
        }
        var pending = new ArrayList<Integer>();
        for (var index = 0; index < seats.size(); index++) {
            var seat = seats.get(index);
            var state = snapshot.get().states().get(index);
            if (state.getPhase() == Phase.GAME_ENDED) {
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
        var read = settled == null ? readCheckpoints() : settled.checkpoints();
        settled = null;
        var target = earliest(read.game().getNextDeadline(), read.timeline().getNextDeadline());
        if (target == null) {
            sleeper.sleep(barrier.pollInterval());
            return;
        }
        advance(gameControl, GAME_SERVICE, target, read.game());
        advance(timelineControl, TIMELINE_SERVICE, target, read.timeline());
        knownLogicalTime = null;
    }

    @Override
    public Optional<AuthoritativeEnding> ending() {
        var snapshot = settle();
        if (snapshot.isEmpty()) {
            return Optional.empty();
        }
        var states = new ArrayList<PlayerGameStateResponse>();
        for (var state : snapshot.get().states()) {
            if (state.getPhase() != Phase.GAME_ENDED || state.getResult() == null) {
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
        if (scores.isEmpty() || !scores.get().equals(scoresByPlayer(result)) || !published(result)) {
            return Optional.empty();
        }
        var eras = states.stream()
                .mapToInt(PlayerGameStateResponse::getEraNumber)
                .max()
                .orElse(0);
        return Optional.of(new AuthoritativeEnding(
                EndReason.valueOf(result.getEndReason().name()), winners(result), finalScores, eras));
    }

    /** The game's logical time as its game service reports it; it stamps the steps retained as evidence. */
    public Instant logicalTime() {
        if (knownLogicalTime == null) {
            checkpoint(gameControl, GAME_SERVICE);
        }
        return knownLogicalTime;
    }

    /**
     * Whether the observed {@code GameEnded} fact agrees with the participant result. Until the observer has
     * seen it with matching final scores the ending is incomplete, never a zero-score game.
     */
    private boolean published(GameResult result) {
        return events.gameEnded()
                .filter(fact -> fact.endReason()
                        == EndReason.valueOf(result.getEndReason().name()))
                .filter(fact -> fact.finalScores().equals(scoresByPlayer(result)))
                .isPresent();
    }

    /** Waits for the game to settle and keeps what the settling check read for the decisions that follow it. */
    private Optional<Settled> settle() {
        settled = null;
        var spent = new Spent();
        var started = waits.now();
        try {
            while (true) {
                var checkpoints = timed(LaneWaits.Kind.CAUSAL_BARRIER, this::readCheckpoints, spent);
                var drained = checkpoints.drained();
                var projection = timed(
                        LaneWaits.Kind.PROJECTION_BARRIER,
                        () -> drained ? projectionSettled(checkpoints) : new Projection(false, List.of()),
                        spent);
                if (drained && projection.settled()) {
                    settled = new Settled(checkpoints, projection.states());
                    return Optional.of(settled);
                }
                if (waits.now() - started >= barrier.timeout().toNanos()) {
                    return Optional.empty();
                }
                // The pause is spent on whichever barrier was still unmet.
                var pausing = waits.now();
                sleeper.sleep(barrier.pollInterval());
                spent.add(
                        drained ? LaneWaits.Kind.PROJECTION_BARRIER : LaneWaits.Kind.CAUSAL_BARRIER,
                        waits.now() - pausing);
            }
        } finally {
            waits.waited(LaneWaits.Kind.CAUSAL_BARRIER, spent.causal);
            waits.waited(LaneWaits.Kind.PROJECTION_BARRIER, spent.projection);
        }
    }

    /** Runs one barrier check, charging its duration to the barrier even when the check throws. */
    private <T> T timed(LaneWaits.Kind kind, Supplier<T> check, Spent spent) {
        var started = waits.now();
        try {
            return check.get();
        } finally {
            spent.add(kind, waits.now() - started);
        }
    }

    /** Nanoseconds one settle wait has spent on each barrier. */
    private static final class Spent {
        private long causal;
        private long projection;

        void add(LaneWaits.Kind kind, long nanos) {
            if (kind == LaneWaits.Kind.CAUSAL_BARRIER) {
                causal += nanos;
            } else {
                projection += nanos;
            }
        }
    }

    private Optional<Map<UUID, Integer>> scores() {
        try {
            var body = scoring.getScores(gameId()).getBody();
            if (body == null) {
                return Optional.empty();
            }
            var scores = new HashMap<UUID, Integer>();
            body.getScores().forEach(score -> scores.put(score.getPlayerId(), score.getScore()));
            return Optional.of(scores);
        } catch (RestClientException _) {
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
                            && Faction.valueOf(winner.getFaction().name()) != planned) {
                        throw new AttemptFailedException(
                                FailureCode.CONFIGURATION_DRIFT,
                                "Seat " + seat + " was revealed as "
                                        + winner.getFaction().name() + " but planned as " + planned.name());
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
                && winnerFacts(a).equals(winnerFacts(b))
                && scoresByPlayer(a).equals(scoresByPlayer(b));
    }

    /** Each winner with its revealed faction and win type, so seats must agree on all three. */
    private static Set<String> winnerFacts(GameResult result) {
        return result.getWinners().stream()
                .map(winner -> winner.getPlayerId() + "|" + winner.getFaction() + "|" + winner.getWinType())
                .collect(Collectors.toSet());
    }

    private int seatOf(UUID playerId) {
        return seats.stream()
                .filter(seat -> seat.playerId().equals(playerId))
                .findFirst()
                .orElseThrow(() -> new AttemptFailedException(
                        FailureCode.CONTRACT_MISMATCH, "The result names an unknown player " + playerId))
                .seatIndex();
    }

    private Faction factionOf(UUID playerId) {
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
                    || !checkpoint.getManifestDigest().equals(context.manifestDigest())
                    || !checkpoint.getRulesDigest().equals(context.rules().digest())) {
                throw new AttemptFailedException(
                        FailureCode.CONFIGURATION_DRIFT,
                        service + " executes a different case, manifest or rules than the attempt configured");
            }
            if (control == gameControl) {
                knownLogicalTime = checkpoint.getLogicalTime().toInstant();
            }
            return checkpoint;
        } catch (RestClientException e) {
            throw new AttemptFailedException(
                    FailureCode.EXECUTION_FAILED,
                    "The " + service + " checkpoint could not be read: " + e.getMessage(),
                    e);
        }
    }

    /** @param known the checkpoint just read, which the first attempt uses; only a stale revision re-reads */
    private void advance(
            SimulationExecutionApi control, String service, OffsetDateTime target, ExecutionCheckpoint known) {
        for (var retry = 0; retry <= CLOCK_RETRIES; retry++) {
            var current = retry == 0 ? known : checkpoint(control, service);
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
