package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.temporalrift.workbench.execution.domain.evidence.StepEntropy;
import io.github.temporalrift.workbench.execution.domain.evidence.StepOutcome;
import io.github.temporalrift.workbench.execution.domain.evidence.StepRecord;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.shared.window.WindowRef;

/**
 * Retains every command a seat sends, with the entitled observation it decided from. The step is appended
 * as unacknowledged before the command is sent, so a command the service accepted is never missing from the
 * evidence however the process ends; it is resolved once the answer, or accepted state, is known. Rejections
 * are retained too, so the entropy draw a decision used is identified by how many rejections preceded it.
 */
public class StepRecorder {

    private final EvidenceLedger evidence;
    private final UUID scopeId;
    private final UUID gameId;
    private final UUID attemptId;
    private final String policySeed;
    private final Supplier<Instant> logicalTime;
    private final Map<Draw, Integer> rejections = new HashMap<>();

    /**
     * @param scopeId the logical case, or the reproduction, whose evidence this is
     * @param policySeed the case seed the policies derive their entropy from
     * @param logicalTime the game's current logical time, which stamps each step
     */
    public StepRecorder(
            EvidenceLedger evidence,
            UUID scopeId,
            UUID gameId,
            UUID attemptId,
            String policySeed,
            Supplier<Instant> logicalTime) {
        this.evidence = evidence;
        this.scopeId = scopeId;
        this.gameId = gameId;
        this.attemptId = attemptId;
        this.policySeed = policySeed;
        this.logicalTime = logicalTime;
    }

    /** Retains the command about to be sent, as unacknowledged. */
    public void sending(EntitledObservation observation, Candidate candidate) {
        var window = observation.window();
        var seat = observation.seatIndex();
        var draws = rejections.getOrDefault(new Draw(seat, window.ref()), 0);
        evidence.append(
                scopeId,
                gameId,
                attemptId,
                new StepRecord(
                        0,
                        seat,
                        window.ref(),
                        logicalTime.get(),
                        observation,
                        candidate,
                        StepOutcome.UNACKNOWLEDGED,
                        null,
                        StepEntropy.ofPolicy(policySeed, seat, window.ref(), draws)));
    }

    public void resolved(int seatIndex, WindowRef window, StepOutcome outcome) {
        resolved(seatIndex, window, outcome, null);
    }

    /** Resolves the unacknowledged step; a rejection advances the entropy draw of the next decision. */
    public void resolved(int seatIndex, WindowRef window, StepOutcome outcome, String code) {
        evidence.resolve(scopeId, gameId, seatIndex, window, outcome, code);
        if (outcome == StepOutcome.REJECTED) {
            rejections.merge(new Draw(seatIndex, window), 1, Integer::sum);
        }
    }

    private record Draw(int seatIndex, WindowRef window) {}
}
