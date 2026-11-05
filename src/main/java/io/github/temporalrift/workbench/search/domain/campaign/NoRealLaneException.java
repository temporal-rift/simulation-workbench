package io.github.temporalrift.workbench.search.domain.campaign;

/** No deployed lane that is build-equivalent to the simulator can play the confirmation. */
public class NoRealLaneException extends RuntimeException {

    public NoRealLaneException(String message) {
        super(message);
    }

    public NoRealLaneException(String message, Throwable cause) {
        super(message, cause);
    }
}
