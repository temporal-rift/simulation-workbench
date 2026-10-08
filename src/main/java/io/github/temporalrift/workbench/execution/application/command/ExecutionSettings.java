package io.github.temporalrift.workbench.execution.application.command;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * Runner tuning that is not part of a frozen experiment.
 *
 * @param lease how long an attempt owns its case without a heartbeat
 * @param heartbeat how often a running attempt renews its lease and checks for cancellation
 * @param maxAttemptsPerCase attempts a case may use before a retryable failure fails the case
 * @param logicalEpoch the logical time every case starts at, identical across attempts and reproductions
 */
public record ExecutionSettings(Duration lease, Duration heartbeat, int maxAttemptsPerCase, Instant logicalEpoch) {

    public ExecutionSettings {
        Objects.requireNonNull(lease, "lease");
        Objects.requireNonNull(heartbeat, "heartbeat");
        Objects.requireNonNull(logicalEpoch, "logicalEpoch");
        if (heartbeat.compareTo(lease) >= 0) {
            throw new IllegalArgumentException("heartbeat must be shorter than the lease");
        }
        if (maxAttemptsPerCase < 1) {
            throw new IllegalArgumentException("maxAttemptsPerCase must be positive");
        }
    }
}
