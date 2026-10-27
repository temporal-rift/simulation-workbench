/**
 * Validates that the in-process simulator plays the same game as the real services: plays a fixed case matrix on the
 * simulator, reproduces every case on one real lane, and records whether the simulator's service source revisions
 * passed, with no tolerance for a divergence.
 */
package io.github.temporalrift.workbench.fidelity;
