package io.github.temporalrift.workbench.execution.domain.run;

import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.github.temporalrift.workbench.shared.text.Sha256;

/**
 * What a lane must be to take a case: the content digest, the builds of the three services and the contract versions
 * they implement. The rules a case plays under are not part of it, because every case carries its own rules into the
 * context it opens. Two fits are equal exactly when their {@code digest}s are.
 *
 * @param digest the SHA-256 of the canonical description of the environment
 */
public record LaneFit(String digest) {

    public LaneFit {
        if (!BundleDigests.isDigest(digest)) {
            throw new IllegalArgumentException("digest must be 64 lowercase hex characters: " + digest);
        }
    }

    /** The fit of a lane that serves content {@code contentDigest} with this provenance. */
    public static LaneFit of(String contentDigest, BundleProvenance provenance) {
        return of(
                contentDigest,
                provenance.gameService(),
                provenance.timelineService(),
                provenance.readService(),
                provenance.contracts());
    }

    /** The fit of an environment of the given content, service builds and contract versions. */
    public static LaneFit of(
            String contentDigest,
            BundleProvenance.ServiceBuild gameService,
            BundleProvenance.ServiceBuild timelineService,
            BundleProvenance.ServiceBuild readService,
            Map<String, String> contracts) {
        Objects.requireNonNull(contentDigest, "contentDigest");
        var description = new StringBuilder("content=").append(contentDigest).append('\n');
        build(description, "game", gameService);
        build(description, "timeline", timelineService);
        build(description, "read", readService);
        new TreeMap<>(contracts)
                .forEach((module, version) -> description
                        .append("contract.")
                        .append(module)
                        .append('=')
                        .append(version)
                        .append('\n'));
        return new LaneFit(Sha256.hexUtf8(description.toString()));
    }

    private static void build(StringBuilder description, String service, BundleProvenance.ServiceBuild build) {
        description
                .append(service)
                .append('=')
                .append(build.imageDigest())
                .append('@')
                .append(build.sourceRevision())
                .append('\n');
    }
}
