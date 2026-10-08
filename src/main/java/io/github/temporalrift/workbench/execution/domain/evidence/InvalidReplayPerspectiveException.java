package io.github.temporalrift.workbench.execution.domain.evidence;

/**
 * The replay was asked for with a perspective and seat that do not fit (published as
 * {@code INVALID_REPLAY_PERSPECTIVE}).
 */
public class InvalidReplayPerspectiveException extends RuntimeException {

    public InvalidReplayPerspectiveException(String reason) {
        super(reason);
    }
}
