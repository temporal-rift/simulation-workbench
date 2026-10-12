package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;

/**
 * One isolated lane: the three services that make up an independent deployment, the bundle they serve, the operator
 * credential for its execution controls, the broker and topics its services publish on, and the bot identities that
 * play its seats.
 * Credentials come from runtime configuration only and are never stored with experiments, runs or evidence.
 *
 * @param kafkaBootstrapServers the broker of the lane, or {@code null} for the workbench default broker
 */
public record LaneEndpoints(
        String id,
        String gameServiceUrl,
        String timelineServiceUrl,
        String readServiceUrl,
        String operatorToken,
        BundleDigests bundle,
        String gameEventsTopic,
        String timelineEventsTopic,
        String kafkaBootstrapServers,
        List<BotIdentity> bots) {

    public LaneEndpoints {
        Objects.requireNonNull(bundle, "bundle");
        kafkaBootstrapServers =
                kafkaBootstrapServers == null || kafkaBootstrapServers.isBlank() ? null : kafkaBootstrapServers;
        bots = List.copyOf(bots);
    }

    /** A bot's player identity and its bearer token; bots are assigned to seats in order. */
    public record BotIdentity(UUID playerId, String token) {}

    /**
     * How the runner waits for the services and projection to settle.
     *
     * @param pollInterval pause between settle checks
     * @param stablePolls consecutive checks with an unchanged projection revision that count as settled
     * @param maxPolls checks made per wait before the game is reported as still waiting
     */
    public record Barrier(Duration pollInterval, int stablePolls, int maxPolls) {}
}
