package io.github.temporalrift.workbench.execution.domain.port.out;

import java.time.Duration;

import io.github.temporalrift.workbench.execution.domain.run.AttemptState;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;

/** Observes the wall time of each attempt from its start to its final state. */
@FunctionalInterface
public interface AttemptTimings {

    /**
     * @param laneId the lane the attempt played on
     * @param players how many seats the case has
     * @param failure why the attempt failed, or {@code null} when it did not fail
     */
    void ended(String laneId, int players, AttemptState state, FailureCode failure, Duration elapsed);

    static AttemptTimings untimed() {
        return (_, _, _, _, _) -> {};
    }
}
