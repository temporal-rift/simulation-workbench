package io.github.temporalrift.workbench.execution.domain.run;

/**
 * A lane's service refused the case because it still holds state of an earlier case. The lane has to be reset before
 * it hosts anything, and the case has not been played: its claim goes back unspent, with no attempt failure.
 */
public class LaneNotCleanException extends RuntimeException {

    public LaneNotCleanException(String message) {
        super(message);
    }
}
