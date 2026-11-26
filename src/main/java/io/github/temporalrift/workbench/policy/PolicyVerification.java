package io.github.temporalrift.workbench.policy;

/**
 * What resolving a manifest policy entry found.
 *
 * @param verdict the outcome
 * @param parameter the parameter an unknown-parameter or invalid-value verdict names, else null
 */
public record PolicyVerification(PolicyReferenceVerdict verdict, String parameter) {}
