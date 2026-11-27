package io.github.temporalrift.workbench.policy.infrastructure.adapter.in.rest;

import java.util.LinkedHashSet;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;

import io.github.temporalrift.workbench.policy.PolicyParameterSpaceDescriptor;
import io.github.temporalrift.workbench.policy.application.port.in.PolicyParameterSpacesUseCase;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.PolicyParameterSpacesApi;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PolicyParameterDefinition;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PolicyParameterSpace;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.PolicyParameterSpaceCatalog;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.SearchedSubset;

@RestController
class PolicyParameterSpaceController implements PolicyParameterSpacesApi {

    private final PolicyParameterSpacesUseCase spaces;

    PolicyParameterSpaceController(PolicyParameterSpacesUseCase spaces) {
        this.spaces = spaces;
    }

    @Override
    public ResponseEntity<PolicyParameterSpaceCatalog> listPolicyParameterSpaces() {
        return ResponseEntity.ok(new PolicyParameterSpaceCatalog(spaces.list().stream()
                .map(PolicyParameterSpaceController::space)
                .toList()));
    }

    @Override
    public ResponseEntity<PolicyParameterSpace> getPolicyParameterSpace(Integer version) {
        return ResponseEntity.ok(space(spaces.get(version)));
    }

    static PolicyParameterDefinition definition(PolicyParameterSpaceDescriptor.Parameter parameter) {
        return new PolicyParameterDefinition(
                parameter.key(), parameter.defaultValue(), parameter.min(), parameter.max(), parameter.step());
    }

    private static PolicyParameterSpace space(PolicyParameterSpaceDescriptor space) {
        return new PolicyParameterSpace(
                space.version(),
                space.policyId(),
                space.policyVersion(),
                space.parameters().stream()
                        .map(PolicyParameterSpaceController::definition)
                        .toList(),
                space.searchedSubsets().entrySet().stream()
                        .map(entry -> new SearchedSubset(
                                Faction.fromValue(entry.getKey()), new LinkedHashSet<>(entry.getValue())))
                        .toList());
    }
}
