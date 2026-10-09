package io.github.temporalrift.workbench.policy.infrastructure.adapter.in.rest;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.policy.application.port.in.ListPoliciesUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.PoliciesApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PolicyBundle;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PolicyCatalog;

@RestController
class PolicyController implements PoliciesApi {

    private final ListPoliciesUseCase listPolicies;

    PolicyController(ListPoliciesUseCase listPolicies) {
        this.listPolicies = listPolicies;
    }

    @Override
    public ResponseEntity<PolicyCatalog> listPolicies() {
        return ResponseEntity.ok(new PolicyCatalog(listPolicies.handle().stream()
                .map(policy -> new PolicyBundle(
                        policy.id(), policy.version(), policy.artifactDigest(), policy.description(), Map.of()))
                .toList()));
    }
}
