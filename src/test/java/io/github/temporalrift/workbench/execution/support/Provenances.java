package io.github.temporalrift.workbench.execution.support;

import java.util.Map;
import java.util.TreeMap;

import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;

/** The provenance test lanes declare for whichever bundle they serve. */
public final class Provenances {

    private Provenances() {}

    public static BundleProvenance sample() {
        return new BundleProvenance(
                new BundleProvenance.Artifact("1".repeat(64), "rules"),
                new BundleProvenance.Artifact("2".repeat(64), null),
                new BundleProvenance.ServiceBuild("sha256:" + "3".repeat(64), "abcdef1"),
                new BundleProvenance.ServiceBuild("sha256:" + "4".repeat(64), "abcdef2"),
                new BundleProvenance.ServiceBuild("sha256:" + "5".repeat(64), "abcdef3"),
                new TreeMap<>(Map.of("session-api", "3.0.0")));
    }
}
