package io.github.temporalrift.workbench.execution.application.command;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.Attempt;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.CaseResult;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.domain.run.GameProgress;
import io.github.temporalrift.workbench.execution.domain.run.LogicalCase;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase.DecisionResult;
import io.github.temporalrift.workbench.policy.application.port.in.PlayDecisionWindowUseCase.SeatPolicy;

/**
 * Plays one real game to its reconciled authoritative ending. Every decision window is played through
 * the policy package's window procedure; the services stay authoritative for legality, scoring and the
 * ending, and the driver only decides who acts, when logical time moves, and when to stop waiting.
 */
final class CaseDriver {

    private final DecisionRuntime decisions;
    private final CommandLedger commands;
    private final CaseLedger cases;
    private final Clock clock;
    private final Instant logicalEpoch;

    CaseDriver(
            DecisionRuntime decisions,
            CommandLedger commands,
            CaseLedger cases,
            Clock clock,
            ExecutionSettings settings) {
        this.decisions = decisions;
        this.commands = commands;
        this.cases = cases;
        this.clock = clock;
        this.logicalEpoch = settings.logicalEpoch();
    }

    /**
     * Runs the attempt's game, attaching to the previous attempt's game when the lane still hosts it.
     *
     * @throws AttemptFailedException when the attempt cannot reach an authoritative ending
     */
    CaseResult play(
            LogicalCase logicalCase,
            Attempt attempt,
            Attempt previous,
            ExperimentSource.Plan plan,
            CaseLane lane,
            AttemptGuard guard) {
        var seats = seatPolicies(logicalCase);
        var deadline = clock.instant().plus(Duration.ofSeconds(plan.caseWallTimeoutSeconds()));
        var context = new CaseLane.CaseContext(
                logicalCase.caseId(),
                attempt.attemptId(),
                logicalCase.caseKey(),
                logicalCase.seed(),
                plan.manifestDigest(),
                logicalCase.seats(),
                logicalEpoch);
        var session = lane.open(context, previous == null ? null : previous.gameId());
        cases.recordGame(attempt.attemptId(), session.gameId(), lane.laneId());
        var player = decisions.windowPlayer(session.participants());

        var progress = session.poll();
        while (!(progress instanceof GameProgress.Ended)) {
            guard.checkpoint();
            requireTime(deadline);
            if (progress instanceof GameProgress.Open open) {
                playWindow(player, seats, open.pendingSeats(), plan);
            } else {
                session.advanceClock();
            }
            progress = session.poll();
        }
        playWindow(player, seats, seats.stream().map(SeatPolicy::seatIndex).toList(), plan);
        var ending = awaitEnding(session, guard, deadline);
        var accepted = commands.accepted(logicalCase.caseId());
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
            ExperimentSource.Plan plan) {
        var pending = seats.stream()
                .filter(seat -> pendingSeats.contains(seat.seatIndex()))
                .toList();
        if (pending.isEmpty()) {
            return;
        }
        for (var seatResult : player.play(pending, plan.maxRejectedCandidatesPerWindow())) {
            if (seatResult.result() instanceof DecisionResult.PolicyExhausted exhausted) {
                throw new AttemptFailedException(
                        FailureCode.POLICY_EXHAUSTED,
                        "Seat " + seatResult.seatIndex() + " exhausted its candidates after rejections "
                                + exhausted.rejectionCodes());
            }
        }
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
}
