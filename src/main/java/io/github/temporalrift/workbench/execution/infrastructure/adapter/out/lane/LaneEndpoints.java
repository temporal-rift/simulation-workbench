package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * One isolated lane: the three services that make up an independent deployment, the operator credential
 * for its execution controls, and the bot identities that play its seats. Credentials come from runtime
 * configuration only and are never stored with experiments, runs or evidence.
 */
public record LaneEndpoints(
        String id,
        String gameServiceUrl,
        String timelineServiceUrl,
        String readServiceUrl,
        String operatorToken,
        List<BotIdentity> bots) {

    public LaneEndpoints {
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
