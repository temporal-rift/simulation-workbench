package io.github.temporalrift.workbench.policy.application.port.in;

import java.util.List;

import io.github.temporalrift.workbench.policy.PolicyParameterSpaceDescriptor;

/** Reads the served versions of the policy parameter space. */
public interface PolicyParameterSpacesUseCase {

    /** Every served version, ascending. */
    List<PolicyParameterSpaceDescriptor> list();

    /**
     * One served version.
     *
     * @throws io.github.temporalrift.workbench.policy.domain.PolicyParameterSpaceNotFoundException when not served
     */
    PolicyParameterSpaceDescriptor get(int version);
}
