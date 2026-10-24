package io.github.temporalrift.workbench.experiment.application.query;

import java.util.UUID;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.application.port.in.GetVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.StoredManifests;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;
import io.github.temporalrift.workbench.shared.text.JsonText;

/** Rebuilds a variant's rules from the baseline document retained when its experiment was frozen. */
public class GetVariantRulesQueryHandler implements GetVariantRulesUseCase {

    private final ExperimentRepository experiments;
    private final VariantRules variantRules;

    public GetVariantRulesQueryHandler(ExperimentRepository experiments, VariantRules variantRules) {
        this.experiments = experiments;
        this.variantRules = variantRules;
    }

    @Override
    public EffectiveRules handle(UUID experimentId, String label) {
        var stored =
                experiments.findById(experimentId).orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        var variant = StoredManifests.variant(StoredManifests.tree(stored), label)
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId, label));
        var document = experiments
                .findBaselineDocument(
                        experimentId,
                        variant.path("rulesArtifact").path("digest").asString())
                .orElseThrow(() -> new ExperimentNotFoundException(experimentId, label));
        var baseline = JsonText.readTree(document, "Baseline rules document of experiment " + experimentId);
        return variantRules.frozenRules(EffectiveRules.parse(baseline), variant);
    }
}
