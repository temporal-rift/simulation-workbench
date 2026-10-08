package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Runner configuration. Lane endpoints and bot credentials are supplied by the deployment that provisions
 * the isolated lanes; they live only here and are never persisted with experiments, runs or evidence.
 *
 * @param workerEnabled whether this process executes pending cases
 * @param workerThreads parallel case executions; defaults to one per configured lane
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

    /** One isolated lane and the identities that play in it. */
    public record Lane(
            String id,
            String gameServiceUrl,
            String timelineServiceUrl,
            String readServiceUrl,
            String operatorToken,
            @DefaultValue List<Bot> bots) {}

    /** A bot's player identity and its bearer token. */
    public record Bot(UUID playerId, String token) {}

    public int effectiveWorkerThreads() {
        return workerThreads != null ? workerThreads : Math.max(1, lanes.size());
    }
}
