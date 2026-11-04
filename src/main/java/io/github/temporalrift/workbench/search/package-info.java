/**
 * Runs durable, reproducible configuration search campaigns on the in-process simulator: generates candidate
 * configurations from a baseline and a parameter space, plays them on shared seeds, evaluates them against a balance
 * criterion, discards and allocates by successive halving, and ends with a short list. Every decision is a pure
 * function of the campaign definition and the games' results.
 */
package io.github.temporalrift.workbench.search;
