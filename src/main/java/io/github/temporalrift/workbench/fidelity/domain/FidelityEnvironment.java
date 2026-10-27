package io.github.temporalrift.workbench.fidelity.domain;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * The builds one side of a check ran: each service's image and source revision, the content it served and the
 * contract versions it implements.
 */
public record FidelityEnvironment(
        ServiceBuild gameService,
        ServiceBuild timelineService,
        ServiceBuild readService,
        String contentDigest,
        SortedMap<String, String> contracts) {

    public FidelityEnvironment {
        Objects.requireNonNull(gameService, "gameService");
        Objects.requireNonNull(timelineService, "timelineService");
        Objects.requireNonNull(readService, "readService");
        Objects.requireNonNull(contentDigest, "contentDigest");
        contracts = Collections.unmodifiableSortedMap(new TreeMap<>(contracts));
    }

    /** The image and source revision a service runs from. */
    public record ServiceBuild(String imageDigest, String sourceRevision) {

        public ServiceBuild {
            Objects.requireNonNull(imageDigest, "imageDigest");
            Objects.requireNonNull(sourceRevision, "sourceRevision");
        }
    }

    public SourceRevisions sourceRevisions() {
        return new SourceRevisions(
                gameService.sourceRevision(), timelineService.sourceRevision(), readService.sourceRevision());
    }
}
