package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.execution;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.JsonNode;

import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.ExperimentRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityExperimentRefusedException;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.policy.PolicyCatalog;

/** Prepares and freezes the experiment through the experiment and policy modules. */
public class FidelityExperimentsAdapter implements FidelityExperiments {

    private final ExperimentFactory experiments;
    private final PolicyCatalog policies;

    public FidelityExperimentsAdapter(ExperimentFactory experiments, PolicyCatalog policies) {
        this.experiments = experiments;
        this.policies = policies;
    }

    @Override
    public VariantRules previewVariant(
            String rulesArtifactDigest, int parameterSpaceVersion, Map<String, BigDecimal> overrides) {
        try {
            var preview = experiments.previewVariant(rulesArtifactDigest, parameterSpaceVersion, overrides);
            return new VariantRules(preview.effectiveRulesDigest(), preview.overrides());
        } catch (ExperimentRefusedException e) {
            throw new FidelityExperimentRefusedException(e.getMessage(), e);
        }
    }

    @Override
    public List<Policy> baselinePolicies() {
        return policies.list().stream()
                .map(policy -> new Policy(policy.id(), policy.version(), policy.artifactDigest()))
                .toList();
    }

    @Override
    public UUID freeze(UUID idempotencyKey, JsonNode manifest) {
        try {
            return experiments.freeze(idempotencyKey, manifest);
        } catch (ExperimentRefusedException e) {
            throw new FidelityExperimentRefusedException(e.getMessage(), e);
        }
    }
}
