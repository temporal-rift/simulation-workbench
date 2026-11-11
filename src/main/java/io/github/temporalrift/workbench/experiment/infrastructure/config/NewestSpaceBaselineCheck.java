package io.github.temporalrift.workbench.experiment.infrastructure.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;

import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;
import io.github.temporalrift.workbench.experiment.domain.rules.ParameterSpace;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;

/**
 * Warns at startup about each declared baseline that lacks what the newest parameter space needs. New campaigns and
 * the designer form use that space, so a baseline without its keys is refused when first used; the warning names the
 * setting to fix instead. It never stops the start, because the older spaces still serve such a baseline.
 */
class NewestSpaceBaselineCheck implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(NewestSpaceBaselineCheck.class);

    private final ParameterSpaces spaces;
    private final VariantRules variantRules;

    NewestSpaceBaselineCheck(ParameterSpaces spaces, VariantRules variantRules) {
        this.spaces = spaces;
        this.variantRules = variantRules;
    }

    @Override
    public void run(ApplicationArguments args) {
        spaces.all().stream()
                .mapToInt(ParameterSpace::version)
                .max()
                .ifPresent(newest -> variantRules
                        .baselineGaps(newest)
                        .forEach(gap -> LOG.warn(
                                "{}rules-document lacks what parameter space {} needs ({}); new campaigns and designer "
                                        + "variants on it are refused until it carries them",
                                gap.scope(),
                                newest,
                                String.join(", ", gap.missing()))));
    }
}
