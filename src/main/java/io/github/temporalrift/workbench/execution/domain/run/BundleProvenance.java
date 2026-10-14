package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Collections;
import java.util.Objects;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * What a lane declares about the frozen bundle it serves: the artifacts its effective digests were resolved from, the
 * service builds that serve it, and the contract versions those services implement. Every lane serving one bundle
 * declares the same provenance.
 *
 * @param contracts exact versions keyed by contract module name
 */
public record BundleProvenance(
        Artifact rulesArtifact,
        Artifact contentArtifact,
        ServiceBuild gameService,
        ServiceBuild timelineService,
        ServiceBuild readService,
        SortedMap<String, String> contracts) {

    public BundleProvenance {
        Objects.requireNonNull(rulesArtifact, "rulesArtifact");
        Objects.requireNonNull(contentArtifact, "contentArtifact");
        Objects.requireNonNull(gameService, "gameService");
        Objects.requireNonNull(timelineService, "timelineService");
        Objects.requireNonNull(readService, "readService");
        contracts = Collections.unmodifiableSortedMap(new TreeMap<>(contracts));
    }

    /** A content-addressed artifact; {@code name} is {@code null} when the lane declares none. */
    public record Artifact(String digest, String name) {

        public Artifact {
            Objects.requireNonNull(digest, "digest");
        }
    }

    /** The image and source revision a service runs from. */
    public record ServiceBuild(String imageDigest, String sourceRevision) {

        public ServiceBuild {
            Objects.requireNonNull(imageDigest, "imageDigest");
            Objects.requireNonNull(sourceRevision, "sourceRevision");
        }
    }
}
