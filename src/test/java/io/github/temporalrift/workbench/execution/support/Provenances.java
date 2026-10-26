package io.github.temporalrift.workbench.execution.support;

import java.util.Map;
import java.util.TreeMap;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

/** The provenance test lanes declare for whichever bundle they serve: exactly what the test manifests pin. */
public final class Provenances {

    private static final BundleProvenance.ServiceBuild BUILD =
            new BundleProvenance.ServiceBuild("sha256:" + "e".repeat(64), "abc1234");

    private Provenances() {}

    public static BundleProvenance sample() {
        return sample("c".repeat(64));
    }

    /**
     * The provenance of a lane serving {@code bundle}: the bundle of the test baseline rules document is named by the
     * artifact that document is retained under, any other by the sample artifact.
     */
    public static BundleProvenance of(BundleDigests bundle) {
        return sample(
                bundle.rulesDigest().equals(VariantRulesFixtures.baseline().digest())
                        ? VariantRulesFixtures.ARTIFACT_DIGEST
                        : "c".repeat(64));
    }

    /** What a case of {@code bundle} needs of a lane: its content on the services the test manifests pin. */
    public static LaneFit fitOf(BundleDigests bundle) {
        return LaneFit.of(bundle.contentDigest(), of(bundle));
    }

    private static BundleProvenance sample(String rulesArtifactDigest) {
        return new BundleProvenance(
                new BundleProvenance.Artifact(rulesArtifactDigest, null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                BUILD,
                BUILD,
                BUILD,
                new TreeMap<>(Map.of("session-api", "3.0.0", "action-api", "7.1.0")));
    }
}
