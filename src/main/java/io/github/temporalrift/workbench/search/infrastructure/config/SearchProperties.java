package io.github.temporalrift.workbench.search.infrastructure.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.workbench.search.application.command.CampaignSettings;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment.CampaignExperimentsAdapter;

/**
 * Configuration sizing the search campaigns.
 *
 * @param workerEnabled whether this process advances campaigns
 * @param pollInterval how long the worker waits when a step changed nothing
 * @param maxCasesPerWave the most cases one wave may have; a larger round is split into several waves
 * @param caseWallTimeoutSeconds how long one simulator case may take
 * @param maxRejectedCandidatesPerWindow how many refused candidates a decision window may spend
 */
@ConfigurationProperties("workbench.search")
public record SearchProperties(
        @DefaultValue("true") boolean workerEnabled,
        @DefaultValue("2s") Duration pollInterval,
        @DefaultValue("50000") int maxCasesPerWave,
        @DefaultValue("3600") int caseWallTimeoutSeconds,
        @DefaultValue("10") int maxRejectedCandidatesPerWindow) {

    CampaignSettings settings() {
        return new CampaignSettings(maxCasesPerWave);
    }

    CampaignExperimentsAdapter.Bounds bounds() {
        return new CampaignExperimentsAdapter.Bounds(caseWallTimeoutSeconds, maxRejectedCandidatesPerWindow);
    }
}
