package io.github.temporalrift.workbench.execution.support;

import java.util.Map;
import java.util.TreeMap;

import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;

/** The provenance test lanes declare for whichever bundle they serve: exactly what the test manifests pin. */
public final class Provenances {

    private static final BundleProvenance.ServiceBuild BUILD =
            new BundleProvenance.ServiceBuild("sha256:" + "e".repeat(64), "abc1234");

    private Provenances() {}

    public static BundleProvenance sample() {
        return new BundleProvenance(
                new BundleProvenance.Artifact("c".repeat(64), null),
                new BundleProvenance.Artifact("d".repeat(64), null),
                BUILD,
                BUILD,
                BUILD,
                new TreeMap<>(Map.of("session-api", "3.0.0", "action-api", "7.1.0")));
    }
}
