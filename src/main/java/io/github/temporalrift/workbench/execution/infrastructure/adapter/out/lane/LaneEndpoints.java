package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.domain.run.LaneFit;

/**
 * One isolated lane: the three services that make up an independent deployment, the bundle they serve and its
 * provenance, the operator credential for its execution controls, the broker and topics its services publish on, the
 * hook that resets it, and the bot identities that play its seats.
 * Credentials come from runtime configuration only and are never stored with experiments, runs or evidence.
 *
 * @param kafkaBootstrapServers the broker of the lane, or {@code null} for the workbench default broker
 * @param resetUrl the deployment hook that makes the lane clean after a failed context close, or {@code null} when
 *     the lane has none
 */
public record LaneEndpoints(
        String id,
        String gameServiceUrl,
        String timelineServiceUrl,
        String readServiceUrl,
        String operatorToken,
        BundleDigests bundle,
        BundleProvenance provenance,
        String gameEventsTopic,
        String timelineEventsTopic,
        String kafkaBootstrapServers,
        URI resetUrl,
        List<BotIdentity> bots) {

    public LaneEndpoints {
        Objects.requireNonNull(bundle, "bundle");
        Objects.requireNonNull(provenance, "provenance");
        kafkaBootstrapServers =
                kafkaBootstrapServers == null || kafkaBootstrapServers.isBlank() ? null : kafkaBootstrapServers;
        bots = List.copyOf(bots);
    }

    /** What a case needs of a lane for this lane to take it. */
    public LaneFit fit() {
        return LaneFit.of(bundle.contentDigest(), provenance);
    }

    /** The lane as the roster tracks its availability: resettable when it has a reset hook. */
    public LaneRoster.Member member() {
        return new LaneRoster.Member(id, fit(), resetUrl != null);
    }

    /** A bot's player identity and its bearer token; bots are assigned to seats in order. */
    public record BotIdentity(UUID playerId, String token) {}

    /**
     * How the runner waits for the services and projection to settle.
     *
     * @param pollInterval pause between settle checks
     * @param timeout how long one wait may last before the game is reported as still waiting
     */
    public record Barrier(Duration pollInterval, Duration timeout) {}
}
