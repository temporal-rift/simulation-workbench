package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BiConsumer;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.workbench.execution.LaneService;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.experiment.ManifestFormat;

/**
 * Runner configuration. Lane endpoints and bot credentials are supplied by the deployment that provisions
 * the isolated lanes; they live only here and are never persisted with experiments, runs or evidence.
 *
 * @param workerEnabled whether this process executes pending cases
 * @param workerThreads parallel case executions; defaults to one per configured lane and simulator slot
 * @param resetTimeout how long one call of a lane's reset hook may take
 */
@ConfigurationProperties("workbench.execution")
public record ExecutionProperties(
        @DefaultValue("true") boolean workerEnabled,
        Integer workerThreads,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("60s") Duration lease,
        @DefaultValue("10s") Duration heartbeat,
        @DefaultValue("2") int maxAttemptsPerCase,
        @DefaultValue("2026-01-01T00:00:00Z") Instant logicalEpoch,
        @DefaultValue("5m") Duration resetTimeout,
        @DefaultValue Barrier barrier,
        @DefaultValue Client client,
        @DefaultValue List<Lane> lanes) {

    public ExecutionProperties {
        requireOneDeclarationPerBundle(lanes);
    }

    /** Lanes serving the same effective digests serve one bundle, so they must declare it identically. */
    private static void requireOneDeclarationPerBundle(List<Lane> lanes) {
        var first = new HashMap<BundleDigests, Lane>();
        for (var lane : lanes) {
            var earlier = first.putIfAbsent(lane.bundle(), lane);
            if (earlier != null) {
                var expected = earlier.bundleDeclaration();
                var actual = lane.bundleDeclaration();
                var keys = new LinkedHashSet<>(expected.keySet());
                keys.addAll(actual.keySet());
                keys.stream()
                        .filter(key -> !Objects.equals(expected.get(key), actual.get(key)))
                        .findFirst()
                        .ifPresent(key -> {
                            throw new IllegalArgumentException("workbench.execution.lanes[id=" + earlier.id()
                                    + "] and workbench.execution.lanes[id=" + lane.id()
                                    + "] serve the same rules and content digests but declare different " + key);
                        });
            }
        }
    }

    /** How long the runner waits for the services and projection to settle. */
    public record Barrier(
            @DefaultValue("50ms") Duration pollInterval,
            @DefaultValue("10s") Duration timeout) {}

    /** Timeouts of the generated HTTP clients. */
    public record Client(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}

    /**
     * One isolated lane, the identities that play in it, and the frozen bundle it serves: its effective rules and
     * content digests and the provenance the deployment that provisioned it declares.
     *
     * @param rulesDocument the lane's baseline {@code EffectiveRules} document; its digest must equal {@code
     *     rulesDigest}
     * @param rulesArtifactName the rules artifact's name, or {@code null} when the deployment names none
     * @param contentArtifactName the content artifact's name, or {@code null} when the deployment names none
     * @param contracts exact versions keyed by contract module name
     * @param resetUrl the deployment hook that makes the lane clean for another case, or {@code null} when the lane is
     *     never reset
     */
    public record Lane(
            String id,
            String gameServiceUrl,
            String timelineServiceUrl,
            String readServiceUrl,
            String operatorToken,
            String rulesDigest,
            Path rulesDocument,
            String contentDigest,
            String rulesArtifactDigest,
            String rulesArtifactName,
            String contentArtifactDigest,
            String contentArtifactName,
            Services services,
            Map<String, String> contracts,
            @DefaultValue("game.events") String gameEventsTopic,
            @DefaultValue("timeline.events") String timelineEventsTopic,
            String kafkaBootstrapServers,
            String resetUrl,
            @DefaultValue List<Bot> bots) {

        private static final String SERVICES_PREFIX = "services.";

        public Lane {
            var scope = scope(id);
            BundleChecks.digest(scope, "rules-digest", rulesDigest);
            BundleChecks.document(scope, "rules-document", rulesDocument);
            BundleChecks.digest(scope, "content-digest", contentDigest);
            BundleChecks.digest(scope, "rules-artifact-digest", rulesArtifactDigest);
            BundleChecks.digest(scope, "content-artifact-digest", contentArtifactDigest);
            BundleChecks.name(scope, "rules-artifact-name", rulesArtifactName);
            BundleChecks.name(scope, "content-artifact-name", contentArtifactName);
            requireServices(scope, services);
            contracts = BundleChecks.contracts(scope, contracts);
            requireResetUrl(id, resetUrl);
        }

        public BundleDigests bundle() {
            return new BundleDigests(rulesDigest, contentDigest);
        }

        public BundleProvenance provenance() {
            return new BundleProvenance(
                    new BundleProvenance.Artifact(rulesArtifactDigest, rulesArtifactName),
                    new BundleProvenance.Artifact(contentArtifactDigest, contentArtifactName),
                    services.gameService().build(),
                    services.timelineService().build(),
                    services.readService().build(),
                    new TreeMap<>(contracts));
        }

        /** Everything the lane declares about its bundle, keyed by property name in a stable order. */
        Map<String, String> bundleDeclaration() {
            var declaration = new LinkedHashMap<String, String>();
            declaration.put("rules-artifact-digest", rulesArtifactDigest);
            declaration.put("rules-artifact-name", rulesArtifactName);
            declaration.put("content-artifact-digest", contentArtifactDigest);
            declaration.put("content-artifact-name", contentArtifactName);
            services.forEach((name, service) -> {
                declaration.put(SERVICES_PREFIX + name.serviceName() + ".image-digest", service.imageDigest());
                declaration.put(SERVICES_PREFIX + name.serviceName() + ".source-revision", service.sourceRevision());
            });
            contracts.forEach((module, version) -> declaration.put("contracts." + module, version));
            return declaration;
        }

        private static void requireResetUrl(String laneId, String value) {
            if (value != null && !isAbsoluteHttpUrl(value)) {
                throw new IllegalArgumentException(scope(laneId) + "reset-url must be an absolute http or https URL");
            }
        }

        private static boolean isAbsoluteHttpUrl(String value) {
            try {
                var uri = new URI(value);
                return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                        && uri.getHost() != null;
            } catch (URISyntaxException _) {
                return false;
            }
        }

        private static void requireServices(String scope, Services services) {
            var declared = services == null ? new Services(null, null, null) : services;
            declared.forEach((name, service) -> {
                var prefix = SERVICES_PREFIX + name.serviceName() + ".";
                var image = service == null ? null : service.imageDigest();
                var revision = service == null ? null : service.sourceRevision();
                BundleChecks.require(
                        scope,
                        prefix + "image-digest",
                        image,
                        ManifestFormat.isImageDigest(image),
                        "must be sha256: followed by 64 lowercase hex characters");
                BundleChecks.sourceRevision(scope, prefix + "source-revision", revision);
            });
        }

        /** The property prefix naming this lane in a startup failure. */
        public String scope() {
            return scope(id);
        }

        private static String scope(String laneId) {
            return "workbench.execution.lanes[id=" + laneId + "].";
        }

        @Override
        public String toString() {
            return "Lane[id=" + id + ", bots=" + bots.size() + "]";
        }
    }

    /** The builds of the three services a lane runs. */
    public record Services(Service gameService, Service timelineService, Service readService) {

        /** Visits each service under its property name in a stable order; an undeclared one is {@code null}. */
        void forEach(BiConsumer<LaneService, Service> visitor) {
            visitor.accept(LaneService.GAME, gameService);
            visitor.accept(LaneService.TIMELINE, timelineService);
            visitor.accept(LaneService.READ, readService);
        }
    }

    /** The image and source revision a service runs from. */
    public record Service(String imageDigest, String sourceRevision) {

        BundleProvenance.ServiceBuild build() {
            return new BundleProvenance.ServiceBuild(imageDigest, sourceRevision);
        }
    }

    /** A bot's player identity and its bearer token. */
    public record Bot(UUID playerId, String token) {

        @Override
        public String toString() {
            return "Bot[playerId=" + playerId + "]";
        }
    }

    /** One worker per lane and simulator slot unless configured. */
    public int effectiveWorkerThreads(int simulatorSlots) {
        return workerThreads != null ? workerThreads : Math.max(1, lanes.size() + simulatorSlots);
    }
}
