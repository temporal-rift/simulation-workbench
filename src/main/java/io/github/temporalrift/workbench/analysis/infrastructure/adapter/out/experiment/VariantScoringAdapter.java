package io.github.temporalrift.workbench.analysis.infrastructure.adapter.out.experiment;

import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

import io.github.temporalrift.workbench.analysis.domain.port.out.VariantScoring;
import io.github.temporalrift.workbench.experiment.EffectiveRules;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;

/** Reads a variant's scoring rules from its effective rules, through the experiment module's public API. */
public class VariantScoringAdapter implements VariantScoring {

    private static final String SCORE_DELTAS = "game.rules.scoring.score-deltas.";
    private static final String MAX_ERAS = "game.rules.max-eras";

    private final ExperimentCatalog catalog;

    public VariantScoringAdapter(ExperimentCatalog catalog) {
        this.catalog = catalog;
    }

    @Override
    public Optional<Rules> rules(UUID experimentId, String variantLabel) {
        return catalog.variantRules(experimentId, variantLabel).map(VariantScoringAdapter::rules);
    }

    private static Rules rules(EffectiveRules rules) {
        var deltas = new HashMap<String, Integer>();
        rules.properties().forEach((key, value) -> {
            if (key.startsWith(SCORE_DELTAS) && value.isNumber()) {
                deltas.put(key.substring(SCORE_DELTAS.length()), value.intValue());
            }
        });
        var maxEras = rules.number(MAX_ERAS)
                .orElseThrow(() -> new IllegalStateException("the variant's rules carry no " + MAX_ERAS))
                .intValueExact();
        return new Rules(deltas, maxEras);
    }
}
