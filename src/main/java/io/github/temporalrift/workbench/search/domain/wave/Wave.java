package io.github.temporalrift.workbench.search.domain.wave;

import java.util.UUID;

/**
 * One batch of games a campaign plays: a frozen experiment and the run that plays it.
 *
 * @param index the wave's place in the campaign, from 0
 * @param round the round the wave belongs to
 */
public record Wave(int index, int round, UUID experimentId, UUID runId) {}
