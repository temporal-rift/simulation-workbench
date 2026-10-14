package io.github.temporalrift.workbench.experiment;

import java.util.List;

/**
 * The bundles the configured lanes serve, which a designer may select for a variant. The experiment module declares
 * what it needs to know; the module that owns the lanes supplies it.
 */
public interface LaneBundleCatalog {

    /** One bundle per distinct effective digests, ordered by rules digest and then content digest. */
    List<LaneBundle> bundles();
}
