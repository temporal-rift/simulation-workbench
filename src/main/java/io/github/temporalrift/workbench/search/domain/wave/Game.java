package io.github.temporalrift.workbench.search.domain.wave;

/**
 * A candidate playing one seed, which exactly one wave plays. The campaign counts its games from these, so a game
 * can never be counted twice.
 *
 * @param seed the game's seed as a decimal unsigned 64-bit integer
 * @param waveIndex the wave that plays it
 */
public record Game(String candidateId, String seed, int waveIndex) {}
