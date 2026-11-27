package io.github.temporalrift.workbench.policy.application.query;

import java.util.List;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyParameterSpaceDescriptor;
import io.github.temporalrift.workbench.policy.application.port.in.PolicyParameterSpacesUseCase;
import io.github.temporalrift.workbench.policy.domain.PolicyParameterSpaceNotFoundException;

public class PolicyParameterSpacesQueryHandler implements PolicyParameterSpacesUseCase {

    private final PolicyCatalog catalog;

    public PolicyParameterSpacesQueryHandler(PolicyCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<PolicyParameterSpaceDescriptor> list() {
        return catalog.parameterSpaces();
    }

    @Override
    public PolicyParameterSpaceDescriptor get(int version) {
        return catalog.parameterSpaces().stream()
                .filter(space -> space.version() == version)
                .findFirst()
                .orElseThrow(() -> new PolicyParameterSpaceNotFoundException(version));
    }
}
