package io.github.temporalrift.workbench.policy.application.query;

import java.util.List;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyDescriptor;
import io.github.temporalrift.workbench.policy.application.port.in.ListPoliciesUseCase;

public class ListPoliciesQueryHandler implements ListPoliciesUseCase {

    private final PolicyCatalog catalog;

    public ListPoliciesQueryHandler(PolicyCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public List<PolicyDescriptor> handle() {
        return catalog.list();
    }
}
