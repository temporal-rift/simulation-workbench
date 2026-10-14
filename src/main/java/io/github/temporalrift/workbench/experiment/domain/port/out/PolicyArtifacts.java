package io.github.temporalrift.workbench.experiment.domain.port.out;

import java.util.Optional;

/** Driven port naming the artifact digest of a defined policy bundle. */
public interface PolicyArtifacts {

    /** The bundle's artifact digest, or empty when no bundle with that id and version is defined. */
    Optional<String> artifactDigest(String id, String version);
}
