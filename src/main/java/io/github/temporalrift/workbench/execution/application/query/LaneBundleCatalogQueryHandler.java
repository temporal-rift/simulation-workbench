package io.github.temporalrift.workbench.execution.application.query;

import java.util.List;

import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.ServedBundle;
import io.github.temporalrift.workbench.experiment.LaneBundle;
import io.github.temporalrift.workbench.experiment.LaneBundleCatalog;

/** Tells the experiment module which bundles the configured lanes serve, without naming any lane. */
public class LaneBundleCatalogQueryHandler implements LaneBundleCatalog {

    private final LaneProvider lanes;

    public LaneBundleCatalogQueryHandler(LaneProvider lanes) {
        this.lanes = lanes;
    }

    @Override
    public List<LaneBundle> bundles() {
        return lanes.servedBundles().stream()
                .map(LaneBundleCatalogQueryHandler::bundle)
                .toList();
    }

    private static LaneBundle bundle(ServedBundle served) {
        var provenance = served.provenance();
        return new LaneBundle(
                artifact(provenance.rulesArtifact()),
                artifact(provenance.contentArtifact()),
                served.digests().rulesDigest(),
                served.digests().contentDigest(),
                build(provenance.gameService()),
                build(provenance.timelineService()),
                build(provenance.readService()),
                provenance.contracts(),
                served.laneCount());
    }

    private static LaneBundle.Artifact artifact(BundleProvenance.Artifact artifact) {
        return new LaneBundle.Artifact(artifact.digest(), artifact.name());
    }

    private static LaneBundle.ServiceBuild build(BundleProvenance.ServiceBuild build) {
        return new LaneBundle.ServiceBuild(build.imageDigest(), build.sourceRevision());
    }
}
