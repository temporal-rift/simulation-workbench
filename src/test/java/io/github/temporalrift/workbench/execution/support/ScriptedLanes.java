package io.github.temporalrift.workbench.execution.support;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.IntStream;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.execution.domain.run.LaneProfile;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneContexts;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneResets;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneRoster;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ResetScheduler;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.shared.game.EndReason;

/**
 * In-process lanes that host scripted games. Lane availability is the production {@link LaneRoster}: a case's context
 * is closed through {@link RecordingContexts}, lanes are not resettable unless a test gives them a reset hook, and a
 * reset drops the games the lane hosted.
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
    private static final List<UUID> BOT_PLAYER_IDS =
            IntStream.range(0, 5).mapToObj(seat -> new UUID(0xb07L, seat)).toList();
    /** A scheduler that never runs what it is given: a lane that needs recovery stays unavailable. */
    private static final ResetScheduler NEVER = (_, _) -> {};

    private final CommandLedger ledger;
    private final EvidenceLedger evidence;
    private final Clock clock;
    private final List<BundleDigests> defaultBundles;
    private volatile List<BundleDigests> laneBundles;
    private volatile LaneResets resets;
    private volatile ResetScheduler scheduler = NEVER;
    private volatile LaneRoster roster;
    private volatile boolean preparing;
    private final RecordingContexts contexts = new RecordingContexts();
    private final Map<UUID, FakeGame.State> games = new ConcurrentHashMap<>();
    private final Map<UUID, String> laneByGame = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> gameByCaseKey = new ConcurrentHashMap<>();
    private final List<Opened> opened = Collections.synchronizedList(new ArrayList<>());
    private volatile Script script = DECISIVE;

    /** One call to open a game on a lane. */
    public record Opened(UUID caseKey, UUID attemptId, UUID gameId, String laneId, String rulesDigest) {}

    /** Lane {@code lane-i} serves {@code laneBundles[i]}. */
    public ScriptedLanes(CommandLedger ledger, EvidenceLedger evidence, Clock clock, List<BundleDigests> laneBundles) {
        this.ledger = ledger;
        this.evidence = evidence;
        this.clock = clock;
        this.defaultBundles = List.copyOf(laneBundles);
        this.laneBundles = defaultBundles;
        this.roster = prepared();
    }

    /** Reconfigures the idle lanes, as a redeployment would: lane {@code lane-i} serves {@code bundles[i]}. */
    public synchronized void serve(BundleDigests... bundles) {
        laneBundles = List.of(bundles);
        roster = prepared();
    }

    /** Gives every idle lane a reset hook, as a redeployment would, and the scheduler that runs its recoveries. */
    public synchronized void resetThrough(LaneResets hook, ResetScheduler resetScheduler) {
        resets = hook;
        scheduler = resetScheduler;
        roster = prepared();
    }

    /** Lets the recoveries of lanes whose context close failed run through the given scheduler. */
    public synchronized void recoverThrough(ResetScheduler resetScheduler) {
        scheduler = resetScheduler;
        roster = prepared();
    }

    /** Starts the lanes as a new process finds them: unprepared, until the runner prepares them. */
    public synchronized void restart() {
        roster = roster();
    }

    /** The closes of contexts the lanes were asked for. */
    public RecordingContexts contexts() {
        return contexts;
    }

    public void script(Script next) {
        this.script = next;
    }

    public synchronized void reset() {
        script = DECISIVE;
        laneBundles = defaultBundles;
        resets = null;
        scheduler = NEVER;
        contexts.clear();
        roster = prepared();
        games.clear();
        laneByGame.clear();
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
    public Optional<CaseLane> acquire(LaneFit fit, String preferredLaneId) {
        return roster.take(fit, preferredLaneId).map(ScriptedLane::new);
    }

    @Override
    public Optional<CaseLane> acquireExactly(String laneId, LaneFit fit) {
        return roster.takeExactly(laneId, fit).map(ScriptedLane::new);
    }

    @Override
    public List<ServedBundle> servedBundles() {
        return ServedBundle.counted(laneBundles.stream()
                .map(bundle -> ServedBundle.ofLane(bundle, Provenances.of(bundle)))
                .toList());
    }

    /**
     * Lane {@code lane-0} stands for the simulator and the others for deployed lanes; every lane serves its bundle on
     * the builds the test manifests pin and plays seats with the same bot identities.
     */
    @Override
    public Map<String, LaneProfile> profiles() {
        var profiles = new LinkedHashMap<String, LaneProfile>();
        var bundles = laneBundles;
        for (var index = 0; index < bundles.size(); index++) {
            var bundle = bundles.get(index);
            profiles.put(
                    laneId(index),
                    new LaneProfile(
                            Provenances.fitOf(bundle),
                            bundle,
                            Provenances.of(bundle),
                            index == 0 ? LaneProfile.Kind.IN_PROCESS : LaneProfile.Kind.DEPLOYED,
                            BOT_PLAYER_IDS));
        }
        return profiles;
    }

    @Override
    public Map<String, LaneFit> freeLanes() {
        return roster.free();
    }

    @Override
    public void prepare() {
        roster.prepare();
    }

    /** A roster whose lanes are already prepared, as lanes are once the runner started. */
    private LaneRoster prepared() {
        var fresh = roster();
        preparing = true;
        try {
            fresh.prepare();
        } finally {
            preparing = false;
        }
        return fresh;
    }

    private LaneRoster roster() {
        var hook = resets;
        var members = IntStream.range(0, laneBundles.size())
                .mapToObj(index ->
                        new LaneRoster.Member(laneId(index), Provenances.fitOf(laneBundles.get(index)), hook != null))
                .toList();
        return new LaneRoster(
                members,
                new LaneContexts() {
                    @Override
                    public void close(String laneId, UUID caseKey, Set<LaneService> services) {
                        contexts.close(laneId, caseKey, services);
                    }

                    @Override
                    public void closeAnyOpen(String laneId) {
                        if (!preparing) {
                            contexts.closeAnyOpen(laneId);
                        }
                    }
                },
                (laneId, caseKey, idempotencyKey) -> {
                    hook.reset(laneId, caseKey, idempotencyKey);
                    dropGamesOf(laneId);
                },
                scheduler);
    }

    private void dropGamesOf(String laneId) {
        laneByGame.entrySet().removeIf(hosted -> {
            var dropped = hosted.getValue().equals(laneId);
            if (dropped) {
                games.remove(hosted.getKey());
            }
            return dropped;
        });
    }

    private static String laneId(int index) {
        return "lane-" + index;
    }

    /** A fresh game gets its own identity, as on a real lane, so a reproduction never reuses the original's. */
    static UUID gameId(UUID caseKey, UUID attemptId) {
        return UUID.nameUUIDFromBytes(("game|" + caseKey + "|" + attemptId).getBytes(StandardCharsets.UTF_8));
    }

    /** A decisive game where seat 0 earns exact outcome weights (as through Intercept) only at its terminal window. */
    public static Script seatZeroEarnsExactWeightAtTheTerminalWindow() {
        return new Script() {
            @Override
            public GameSession.AuthoritativeEnding ending(CaseLane.CaseContext context) {
                return decisive(context);
            }

            @Override
            public EntitledObservation observation(CaseLane.CaseContext context, EntitledObservation observation) {
                if (observation.seatIndex() != 0
                        || !(observation.window() instanceof DecisionWindow.TerminalReadiness)) {
                    return observation;
                }
                var events = observation.events().stream()
                        .map(event -> new EventView(
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
        };
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

        private final LaneRoster.Tenancy tenancy;

        ScriptedLane(LaneRoster.Tenancy tenancy) {
            this.tenancy = tenancy;
        }

        @Override
        public String laneId() {
            return tenancy.laneId();
        }

        @Override
        public GameSession open(CaseContext context) {
            tenancy.host(context);
            script.onOpen(context);
            tenancy.opened(LaneService.GAME);
            tenancy.opened(LaneService.TIMELINE);
            var gameId = gameId(context.caseKey(), context.attemptId());
            opened.add(new Opened(
                    context.caseKey(),
                    context.attemptId(),
                    gameId,
                    laneId(),
                    context.rules().digest()));
            games.put(gameId, new FakeGame.State());
            laneByGame.put(gameId, laneId());
            gameByCaseKey.put(context.caseKey(), gameId);
            ledger.reset(context.caseId());
            return new FakeGame(context, gameId, games.get(gameId), script, ledger, evidence, clock);
        }

        @Override
        public void close() {
            tenancy.giveBack();
        }
    }
}
