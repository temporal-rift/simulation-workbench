package io.github.temporalrift.workbench.policy.domain;

/** The policy parameter space version asked for is not served. */
public class PolicyParameterSpaceNotFoundException extends RuntimeException {

    public PolicyParameterSpaceNotFoundException(int version) {
        super("No policy parameter space of version " + version + " is served");
    }
}
