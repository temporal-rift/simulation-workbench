package io.github.temporalrift.workbench.policy.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.policy.PolicyDescriptor;

/** Lists the policy bundles a manifest may reference. */
public interface ListPoliciesUseCase {

    List<PolicyDescriptor> handle();
}
