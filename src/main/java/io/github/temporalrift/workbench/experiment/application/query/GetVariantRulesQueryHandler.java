package io.github.temporalrift.workbench.experiment.application.query;

import java.util.UUID;
import java.util.stream.StreamSupport;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.application.port.in.GetVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.domain.ExperimentNotFoundException;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;

/** Rebuilds a variant's rules from the baseline document retained when its experiment was frozen. */
public class GetVariantRulesQueryHandler implements GetVariantRulesUseCase {

    private final ExperimentRepository experiments;
    private final VariantRules variantRules;
    private final ObjectMapper objectMapper;

    public GetVariantRulesQueryHandler(
            ExperimentRepository experiments, VariantRules variantRules, ObjectMapper objectMapper) {
        this.experiments = experiments;
        this.variantRules = variantRules;
        this.objectMapper = objectMapper;
    }

    @Override
    public EffectiveRules handle(UUID experimentId, String label) {
        var stored =
                experiments.findById(experimentId).orElseThrow(() -> new ExperimentNotFoundException(experimentId));
        try {
            var variant = StreamSupport.stream(
                            objectMapper
                                    .readTree(stored.manifestJson())
                                    .get("variants")
                                    .spliterator(),
                            false)
                    .filter(candidate -> label.equals(candidate.path("label").asString()))
                    .findFirst()
                    .orElseThrow(() -> new ExperimentNotFoundException(experimentId, label));
            var document = experiments
                    .findBaselineDocument(
                            experimentId,
                            variant.path("rulesArtifact").path("digest").asString())
                    .orElseThrow(() -> new ExperimentNotFoundException(experimentId, label));
            JsonNode baseline = objectMapper.readTree(document);
            return variantRules.frozenRules(EffectiveRules.parse(baseline), variant);
        } catch (JacksonException e) {
            throw new IllegalStateException("Stored experiment is not valid JSON: " + experimentId, e);
        }
    }
}
