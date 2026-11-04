package io.github.temporalrift.workbench.search.application.command;

/**
 * How a campaign sizes the experiments that play it.
 *
 * @param maxCasesPerWave the most cases one wave may have, which splits a larger round into several waves
 */
public record CampaignSettings(int maxCasesPerWave) {

    public CampaignSettings {
        if (maxCasesPerWave < 1) {
            throw new IllegalArgumentException("maxCasesPerWave must be at least 1");
        }
    }
}
