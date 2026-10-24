package io.github.temporalrift.workbench.execution;

/** The services a lane runs, each under the name its configuration key and error messages carry. */
public enum LaneService {
    GAME("game-service"),
    TIMELINE("timeline-service"),
    READ("read-service");

    private final String serviceName;

    LaneService(String serviceName) {
        this.serviceName = serviceName;
    }

    public String serviceName() {
        return serviceName;
    }
}
