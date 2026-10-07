/**
 * Versioned baseline bot policies ({@code random-v1}, {@code faction-greedy-v1}) that decide every normal
 * decision window from a frozen entitled observation only. The durable runner supplies observations and
 * submits choices through the {@code ParticipantGateway} port.
 */
package io.github.temporalrift.workbench.policy;
