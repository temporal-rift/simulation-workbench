package io.github.temporalrift.workbench.fidelity.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.workbench.fidelity.application.command.FidelitySettings;

/**
 * Fidelity check configuration.
 *
 * @param workerEnabled whether this process advances fidelity checks
 * @param pollInterval how long the worker waits when a step changed nothing
 * @param caseWallTimeoutSeconds how long one simulator case of a check may take
 * @param maxRejectedCandidatesPerWindow how many refused candidates a decision window of a check may spend
 * @param reproductionBatch how many reproductions one step of a check requests
 * @param maxConcurrency the most cases the experiment of a check may play at once
 */
@ConfigurationProperties("workbench.fidelity")
public record FidelityProperties(
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("5s") Duration pollInterval,
        @DefaultValue("3600") int caseWallTimeoutSeconds,
        @DefaultValue("10") int maxRejectedCandidatesPerWindow,
        @DefaultValue("20") int reproductionBatch,
        @DefaultValue("64") int maxConcurrency) {

    FidelitySettings settings() {
        return new FidelitySettings(
                caseWallTimeoutSeconds, maxRejectedCandidatesPerWindow, reproductionBatch, maxConcurrency);
    }
}
