package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Objects;

/** The source revision of each of the three services: what a fidelity record applies to. */
public record SourceRevisions(String gameService, String timelineService, String readService) {

    public SourceRevisions {
        Objects.requireNonNull(gameService, "gameService");
        Objects.requireNonNull(timelineService, "timelineService");
        Objects.requireNonNull(readService, "readService");
    }
}
