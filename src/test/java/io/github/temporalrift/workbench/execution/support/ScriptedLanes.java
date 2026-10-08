package io.github.temporalrift.workbench.execution.support;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;

/**
 * In-process lanes that host scripted games. A game keeps its state per game id across attempts, exactly
 * as a real lane does, so a recovering attempt that attaches to it sees the decisions already accepted.
 */
public class ScriptedLanes implements LaneProvider {

    /** A scripted game's behavior; tests replace the default to inject endings, delays and faults. */
    public interface Script {

        /** The game's ending once every seat decided. */
        GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context);

        /** Called when an attempt opens the case on a lane; a test may throw an attempt failure here. */
        default void onOpen(CaseLane.CaseContext context) {}

        /** Called at the start of every poll; a test may block here or throw to model a crash. */
        default void onPoll(CaseLane.CaseContext context, FakeGame game) {}

        /** Called when a seat's decision reaches the service; return true to lose the acknowledgement. */
        default boolean loseAcknowledgement(CaseLane.CaseContext context, int seatIndex) {
            return false;
        }

        /** Lets a test change what a seat observes, for example to model a rules drift. */
        default EntitledObservation observation(CaseLane.CaseContext context, EntitledObservation observation) {
            return observation;
        }

        /** A refusal code the service answers a seat's command with, or null to accept it. */
        default String rejection(CaseLane.CaseContext context, int seatIndex) {
            return null;
        }

        /** Whether the ending and final scores are published yet; a test may hold them back. */
        default boolean endingPublished(CaseLane.CaseContext context) {
            return true;
        }
    }

    private static final Script DECISIVE = ScriptedLanes::decisive;

    private final CommandLedger ledger;
    private final EvidenceLedger evidence;
    private final Clock clock;
    private final int laneCount;
    private final Set<String> busy = new HashSet<>();
    private final Map<UUID, FakeGame.State> games = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> gameByCaseKey = new ConcurrentHashMap<>();
    private final List<Opened> opened = Collections.synchronizedList(new ArrayList<>());
    private volatile Script script = DECISIVE;

    /** One call to open a game on a lane. */
    public record Opened(UUID caseKey, UUID attemptId, UUID resumeGameId, UUID gameId) {}

    public ScriptedLanes(CommandLedger ledger, EvidenceLedger evidence, Clock clock, int laneCount) {
        this.ledger = ledger;
        this.evidence = evidence;
        this.clock = clock;
        this.laneCount = laneCount;
    }

    public void script(Script next) {
        this.script = next;
    }

    /** Drops every hosted game, as a lane that was reprovisioned between two attempts would. */
    public void forgetGames() {
        games.clear();
    }

    public void reset() {
        script = DECISIVE;
        games.clear();
        gameByCaseKey.clear();
        opened.clear();
    }

    public List<Opened> opened() {
        synchronized (opened) {
            return List.copyOf(opened);
        }
    }

    /** How many decision submissions reached the service for the case, across every attempt. */
    public int submissionsFor(UUID caseKey) {
        var gameId = gameByCaseKey.get(caseKey);
        var state = gameId == null ? null : games.get(gameId);
        return state == null ? 0 : state.submissions.get();
    }

    @Override
    public synchronized Optional<CaseLane> acquire(String preferredLaneId) {
        for (var index = 0; index < laneCount; index++) {
            var id = "lane-" + index;
            if (busy.add(id)) {
                return Optional.of(new ScriptedLane(id));
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized boolean hasFreeLane() {
        return busy.size() < laneCount;
    }

    private synchronized void release(String laneId) {
        busy.remove(laneId);
    }

    /** A fresh game gets its own identity, as on a real lane, so a reproduction never reuses the original's. */
    static UUID gameId(UUID caseKey, UUID attemptId) {
        return UUID.nameUUIDFromBytes(("game|" + caseKey + "|" + attemptId).getBytes(StandardCharsets.UTF_8));
    }

    /** A decisive game: seat 0 wins on score, every seat has a distinct score. */
    public static GameSession.AuthoritativeEnding decisive(CaseLane.CaseContext context) {
        var scores = context.seats().stream()
                .map(seat -> new FinalScore(seat.seatIndex(), seat.faction(), 30 - seat.seatIndex() * 4))
                .toList();
        var first = context.seats().getFirst();
        return new GameSession.AuthoritativeEnding(
                EndReason.WIN_CONDITION_MET,
                List.of(new Winner(first.seatIndex(), first.faction(), WinType.SCORE_THRESHOLD)),
                scores,
                3);
    }

    /** An abnormal ending: a valid game outcome with no winners. */
    public static GameSession.AuthoritativeEnding withoutWinners(CaseLane.CaseContext context, EndReason reason) {
        var scores = context.seats().stream()
                .map(seat -> new FinalScore(seat.seatIndex(), seat.faction(), 5))
                .toList();
        return new GameSession.AuthoritativeEnding(reason, List.of(), scores, 2);
    }

    private final class ScriptedLane implements CaseLane {

        private final String laneId;

        ScriptedLane(String laneId) {
            this.laneId = laneId;
        }

        @Override
        public String laneId() {
            return laneId;
        }

        @Override
        public GameSession open(CaseContext context, UUID resumeGameId) {
            script.onOpen(context);
            var attach = resumeGameId != null && games.containsKey(resumeGameId);
            var gameId = attach ? resumeGameId : gameId(context.caseKey(), context.attemptId());
            opened.add(new Opened(context.caseKey(), context.attemptId(), resumeGameId, gameId));
            if (!attach) {
                games.put(gameId, new FakeGame.State());
                gameByCaseKey.put(context.caseKey(), gameId);
                ledger.reset(context.caseId());
            }
            var game = new FakeGame(context, gameId, games.get(gameId), script, ledger, evidence, clock);
            if (attach) {
                game.recoverInDoubt();
            }
            return game;
        }

        @Override
        public void close() {
            release(laneId);
        }
    }
}
