/**
 * Durable real-service batches with interruption recovery. Owns the run lifecycle, the scheduling of logical
 * cases with their attempts and leases, command reconciliation against the services' accepted state,
 * cancellation, and resume over frozen experiment manifests. Games are played only through the published
 * participant operations of isolated lanes; the services stay authoritative for rules, outcomes and scores.
 */
package io.github.temporalrift.workbench.execution;
