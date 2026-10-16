package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.nio.file.Path;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * The in-process simulator: how many slots play cases in this JVM, the executable jars of the three services every
 * slot hosts, the rules they play under, and the bundle those builds serve. Every slot hosts the same builds under the
 * same rules, so one declaration serves all of them.
 *
 * @param slots how many cases may play at once; 0 disables the simulator
 * @param rulesFile the {@code game.rules.*} properties (a {@code .yml}, {@code .yaml} or {@code .properties} file)
 *     every service of a slot is started under
 * @param workDirectory where the jars are extracted; defaults to a directory under the JVM temporary directory
 * @param bots how many bot identities each slot mints; at least the largest player count a case may have
 */
@ConfigurationProperties("workbench.simulator")
public record SimulatorProperties(
        @DefaultValue("0") int slots,
        Path gameServiceJar,
        Path timelineServiceJar,
        Path readServiceJar,
        Path rulesFile,
        Path workDirectory,
        @DefaultValue("6") int bots,
        Bundle bundle) {

    private static final String SCOPE = "workbench.simulator.";

    public SimulatorProperties {
        if (slots < 0) {
            throw new IllegalArgumentException(SCOPE + "slots must not be negative");
        }
        if (bots < 1) {
            throw new IllegalArgumentException(SCOPE + "bots must be at least 1");
        }
        if (slots > 0) {
            requirePath("game-service-jar", gameServiceJar);
            requirePath("timeline-service-jar", timelineServiceJar);
            requirePath("read-service-jar", readServiceJar);
            requirePath("rules-file", rulesFile);
            if (bundle == null) {
                throw new IllegalArgumentException(SCOPE + "bundle is required");
            }
        }
    }

    private static void requirePath(String key, Path value) {
        if (value == null) {
            throw new IllegalArgumentException(SCOPE + key + " is required");
        }
    }

    public boolean enabled() {
        return slots > 0;
    }

    /**
     * What the hosted builds declare about the bundle they serve. A service's image digest is the digest of its jar,
     * so only the source revisions are declared.
     *
     * @param contracts exact versions keyed by contract module name
     */
    public record Bundle(
            String rulesDigest,
            String contentDigest,
            String rulesArtifactDigest,
            String rulesArtifactName,
            String contentArtifactDigest,
            String contentArtifactName,
            SourceRevisions sourceRevisions,
            Map<String, String> contracts) {

        private static final String BUNDLE_SCOPE = SCOPE + "bundle.";

        public Bundle {
            BundleChecks.digest(BUNDLE_SCOPE, "rules-digest", rulesDigest);
            BundleChecks.digest(BUNDLE_SCOPE, "content-digest", contentDigest);
            BundleChecks.digest(BUNDLE_SCOPE, "rules-artifact-digest", rulesArtifactDigest);
            BundleChecks.digest(BUNDLE_SCOPE, "content-artifact-digest", contentArtifactDigest);
            BundleChecks.name(BUNDLE_SCOPE, "rules-artifact-name", rulesArtifactName);
            BundleChecks.name(BUNDLE_SCOPE, "content-artifact-name", contentArtifactName);
            var revisions = sourceRevisions == null ? new SourceRevisions(null, null, null) : sourceRevisions;
            BundleChecks.sourceRevision(BUNDLE_SCOPE, "source-revisions.game-service", revisions.gameService());
            BundleChecks.sourceRevision(BUNDLE_SCOPE, "source-revisions.timeline-service", revisions.timelineService());
            BundleChecks.sourceRevision(BUNDLE_SCOPE, "source-revisions.read-service", revisions.readService());
            contracts = BundleChecks.contracts(BUNDLE_SCOPE, contracts);
        }

        public BundleDigests digests() {
            return new BundleDigests(rulesDigest, contentDigest);
        }

        public SortedMap<String, String> sortedContracts() {
            return new TreeMap<>(contracts);
        }
    }

    /** The source revision each service jar was built from. */
    public record SourceRevisions(String gameService, String timelineService, String readService) {}
}
