package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.evidence.DecisionTranscript;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.reproduction.Divergence;
import io.github.temporalrift.workbench.execution.domain.reproduction.TranscriptDivergedException;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase.DecisionResult;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase.SeatPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateCodec;

/**
 * Plays one real game to its reconciled authoritative ending. Every decision window is played through
 * the policy package's window procedure; the services stay authoritative for legality, scoring and the
 * ending, and the driver only decides who acts, when logical time moves, and when to stop waiting.
 * The same loop re-executes a saved case from its pinned transcript instead of from the policies.
 */
public final class CaseDriver {

    private final DecisionRuntime decisions;
    private final CommandLedger commands;
    private final CaseLedger cases;
    private final EvidenceLedger evidence;
    private final Clock clock;
    private final Instant logicalEpoch;

    public CaseDriver(
            DecisionRuntime decisions,
            CommandLedger commands,
            CaseLedger cases,
            EvidenceLedger evidence,
            Clock clock,
            ExecutionSettings settings) {
        this.decisions = decisions;
        this.commands = commands;
        this.cases = cases;
        this.evidence = evidence;
        this.clock = clock;
        this.logicalEpoch = settings.logicalEpoch();
    }

    /** A finished reproduction game: what it produced and where its evidence is. */
    public record Replayed(UUID gameId, CaseResult result) {}

    /**
     * Runs the attempt's game, attaching to the previous attempt's game when the lane still hosts it.
     *
     * @throws AttemptFailedException when the attempt cannot reach an authoritative ending
     */
    public CaseResult play(
            LogicalCase logicalCase,
            Attempt attempt,
            Attempt previous,
            ExperimentSource.Plan plan,
            CaseLane lane,
            AttemptGuard guard) {
        var seats = seatPolicies(logicalCase);
        var deadline = clock.instant().plus(Duration.ofSeconds(plan.caseWallTimeoutSeconds()));
        evidence.pin(logicalCase.caseId(), plan.manifestDigest(), plan.manifestJson(), clock.instant());
        var context = context(logicalCase, logicalCase.caseId(), attempt.attemptId(), plan);
        var session = lane.open(context, previous == null ? null : previous.gameId());
        cases.recordGame(attempt.attemptId(), session.gameId(), lane.laneId());
        var player = decisions.windowPlayer(session.participants());

        var ending = playToEnd(session, player, seats, plan, guard, deadline, (seat, codes) -> {
            throw new AttemptFailedException(
                    FailureCode.POLICY_EXHAUSTED,
                    "Seat " + seat + " exhausted its candidates after rejections " + codes);
        });
        var result = result(logicalCase, logicalCase.caseId(), ending);
        evidence.seal(
                logicalCase.caseId(),
                DecisionTranscript.render(commands.accepted(logicalCase.caseId())),
                result.semanticDigest(),
                clock.instant());
        return result;
    }

    /**
     * Executes the saved case again on a clean lane, deciding in every window exactly what the pinned
     * transcript accepted. The reproduction keeps its own transcript and evidence under {@code scopeId}.
     *
     * @throws TranscriptDivergedException when the transcript cannot be followed to the end
     * @throws AttemptFailedException when the reproduction cannot reach an authoritative ending
     */
    public Replayed replay(
            LogicalCase logicalCase,
            UUID scopeId,
            UUID attemptId,
            DecisionTranscript transcript,
            ExperimentSource.Plan plan,
            CaseLane lane,
            AttemptGuard guard) {
        var deadline = clock.instant().plus(Duration.ofSeconds(plan.caseWallTimeoutSeconds()));
        var session = lane.open(context(logicalCase, scopeId, attemptId, plan), null);
        var player = decisions.windowPlayer(session.participants());
        var policy = new TranscriptPolicy(transcript);
        var policySeed = Long.parseUnsignedLong(logicalCase.seed());
        var seats = logicalCase.seats().stream()
                .map(seat -> new SeatPolicy(seat.seatIndex(), policy, policySeed))
                .toList();

        var ending = playToEnd(session, player, seats, plan, guard, deadline, (seat, codes) -> {
            throw new TranscriptDivergedException(divergence(
                    policy,
                    seat,
                    codes,
                    evidence.steps(scopeId, session.gameId()).size()));
        });
        return new Replayed(session.gameId(), result(logicalCase, scopeId, ending));
    }

    private CaseLane.CaseContext context(
            LogicalCase logicalCase, UUID scopeId, UUID attemptId, ExperimentSource.Plan plan) {
        return new CaseLane.CaseContext(
                scopeId,
                attemptId,
                logicalCase.caseKey(),
                logicalCase.seed(),
                plan.manifestDigest(),
                logicalCase.seats(),
                logicalEpoch);
    }

    private GameSession.AuthoritativeEnding playToEnd(
            GameSession session,
            PlayDecisionWindowUseCase player,
            List<SeatPolicy> seats,
            ExperimentSource.Plan plan,
            AttemptGuard guard,
            Instant deadline,
            Exhaustion onExhausted) {
        var progress = session.poll();
        while (!(progress instanceof GameProgress.Ended)) {
            guard.checkpoint();
            requireTime(deadline);
            if (progress instanceof GameProgress.Open(var pendingSeats)) {
                playWindow(player, seats, pendingSeats, plan, onExhausted);
            } else {
                session.advanceClock();
            }
            progress = session.poll();
        }
        playWindow(player, seats, seats.stream().map(SeatPolicy::seatIndex).toList(), plan, onExhausted);
        return awaitEnding(session, guard, deadline);
    }

    private CaseResult result(LogicalCase logicalCase, UUID scopeId, GameSession.AuthoritativeEnding ending) {
        var accepted = commands.accepted(scopeId);
        return new CaseResult(
                ending.endReason(),
                ending.winners(),
                ending.finalScores(),
                ending.eras(),
                SemanticDigest.rounds(accepted),
                accepted.size(),
                SemanticDigest.of(logicalCase.caseKey(), logicalCase.seed(), logicalCase.seats(), accepted, ending));
    }

    private GameSession.AuthoritativeEnding awaitEnding(GameSession session, AttemptGuard guard, Instant deadline) {
        var ending = session.ending();
        while (ending.isEmpty()) {
            guard.checkpoint();
            requireTime(deadline);
            ending = session.ending();
        }
        return ending.get();
    }

    private void playWindow(
            PlayDecisionWindowUseCase player,
            List<SeatPolicy> seats,
            List<Integer> pendingSeats,
            ExperimentSource.Plan plan,
            Exhaustion onExhausted) {
        var pending = seats.stream()
                .filter(seat -> pendingSeats.contains(seat.seatIndex()))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        for (var seatResult : player.play(pending, plan.maxRejectedCandidatesPerWindow())) {
            if (seatResult.result() instanceof DecisionResult.PolicyExhausted(var rejectionCodes)) {
                onExhausted.exhausted(seatResult.seatIndex(), rejectionCodes);
            }
        }
    }

    /** Where a reproduction could not follow the transcript, as the first divergence. */
    private static Divergence divergence(TranscriptPolicy policy, int seatIndex, List<String> codes, int step) {
        var miss = policy.miss().filter(found -> found.seatIndex() == seatIndex);
        var window = miss.map(TranscriptPolicy.Miss::windowKey).orElse("unknown");
        var refused = miss.map(TranscriptPolicy.Miss::refused).orElse(null);
        if (refused == null) {
            return new Divergence(
                    step,
                    "UNEXPECTED_WINDOW",
                    java.util.Map.of("seatIndex", seatIndex, "decision", "none retained in the transcript"),
                    java.util.Map.of("seatIndex", seatIndex, "window", window));
        }
        return new Divergence(
                step,
                "COMMAND_REJECTED",
                java.util.Map.of(
                        "seatIndex",
                        seatIndex,
                        "window",
                        window,
                        "decision",
                        CandidateCodec.encode(refused),
                        "outcome",
                        "ACCEPTED"),
                java.util.Map.of("seatIndex", seatIndex, "window", window, "outcome", "REJECTED", "codes", codes));
    }

    private List<SeatPolicy> seatPolicies(LogicalCase logicalCase) {
        var policySeed = Long.parseUnsignedLong(logicalCase.seed());
        var seats = new ArrayList<SeatPolicy>();
        for (var plan : logicalCase.seats()) {
            var policy = decisions
                    .policy(plan.policyId(), plan.policyVersion())
                    .orElseThrow(() -> new AttemptFailedException(
                            FailureCode.CONTRACT_MISMATCH,
                            "Policy " + plan.policyId() + "@" + plan.policyVersion() + " is not defined"));
            seats.add(new SeatPolicy(plan.seatIndex(), policy, policySeed));
        }
        return seats;
    }

    private void requireTime(Instant deadline) {
        if (clock.instant().isAfter(deadline)) {
            throw new AttemptFailedException(
                    FailureCode.RUNNER_TIMEOUT, "The case did not reach an authoritative ending in time");
        }
    }

    /** What to do when a seat has no decision left to submit. */
    @FunctionalInterface
    private interface Exhaustion {
        void exhausted(int seatIndex, List<String> rejectionCodes);
    }
}
