package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * Runner configuration. Lane endpoints and bot credentials are supplied by the deployment that provisions
 * the isolated lanes; they live only here and are never persisted with experiments, runs or evidence.
 *
 * @param workerEnabled whether this process executes pending cases
 * @param workerThreads parallel case executions; defaults to one per configured lane
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

    /** How long the runner waits for the services and projection to settle. */
    public record Barrier(
            @DefaultValue("500ms") Duration pollInterval,
            @DefaultValue("2") int stablePolls,
            @DefaultValue("20") int maxPolls) {}

    /** Timeouts of the generated HTTP clients. */
    public record Client(
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration readTimeout) {}

    /**
     * One isolated lane, the identities that play in it, and the effective rules and content digests of the frozen
     * bundle it serves.
     *
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
            String contentDigest,
            @DefaultValue("game.events") String gameEventsTopic,
            @DefaultValue("timeline.events") String timelineEventsTopic,
            String kafkaBootstrapServers,
            String resetUrl,
            @DefaultValue List<Bot> bots) {

        public Lane {
            requireDigest(id, "rules-digest", rulesDigest);
            requireDigest(id, "content-digest", contentDigest);
            requireResetUrl(id, resetUrl);
        }

        private static void requireResetUrl(String laneId, String value) {
            if (value != null && !isAbsoluteHttpUrl(value)) {
                throw new IllegalArgumentException(
                        "workbench.execution.lanes[id=" + laneId + "].reset-url must be an absolute http or https URL");
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

        private static void requireDigest(String laneId, String key, String value) {
            var property = "workbench.execution.lanes[id=" + laneId + "]." + key;
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException(property + " is required");
            }
            if (!BundleDigests.isDigest(value)) {
                throw new IllegalArgumentException(property + " must be 64 lowercase hex characters");
            }
        }

        @Override
        public String toString() {
            return "Lane[id=" + id + ", bots=" + bots.size() + "]";
        }
    }

    /** A bot's player identity and its bearer token. */
    public record Bot(UUID playerId, String token) {

        @Override
        public String toString() {
            return "Bot[playerId=" + playerId + "]";
        }
    }

    public int effectiveWorkerThreads() {
        return workerThreads != null ? workerThreads : Math.max(1, lanes.size());
    }
}
