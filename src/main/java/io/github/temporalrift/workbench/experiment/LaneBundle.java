package io.github.temporalrift.workbench.experiment;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A bundle some configured lane serves: what a variant names when it selects it, the provenance of the services
 * serving it, and how many lanes serve it, busy or free.
 *
 * @param contracts exact versions keyed by contract module name
 */
public record LaneBundle(
        Artifact rulesArtifact,
        Artifact contentArtifact,
        String effectiveRulesDigest,
        String effectiveContentDigest,
        ServiceBuild gameService,
        ServiceBuild timelineService,
        ServiceBuild readService,
        SortedMap<String, String> contracts,
        int laneCount) {

    public LaneBundle {
        Objects.requireNonNull(rulesArtifact, "rulesArtifact");
        Objects.requireNonNull(contentArtifact, "contentArtifact");
        Objects.requireNonNull(effectiveRulesDigest, "effectiveRulesDigest");
        Objects.requireNonNull(effectiveContentDigest, "effectiveContentDigest");
        Objects.requireNonNull(gameService, "gameService");
        Objects.requireNonNull(timelineService, "timelineService");
        Objects.requireNonNull(readService, "readService");
        contracts = Collections.unmodifiableSortedMap(new TreeMap<>(contracts));
    }

    /** A content-addressed artifact; {@code name} is {@code null} when the lane declares none. */
    public record Artifact(String digest, String name) {}

    /** The image and source revision a service runs from. */
    public record ServiceBuild(String imageDigest, String sourceRevision) {}
}
