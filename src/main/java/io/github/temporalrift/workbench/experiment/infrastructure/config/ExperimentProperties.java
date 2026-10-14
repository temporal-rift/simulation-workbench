package io.github.temporalrift.workbench.experiment.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import io.github.temporalrift.workbench.experiment.domain.template.TemplateBounds;

/** Experiment configuration: the execution bounds the templates propose. */
@ConfigurationProperties("workbench.experiment")
public record ExperimentProperties(@DefaultValue Templates templates) {

    public record Templates(
            @DefaultValue("3600") int caseWallTimeoutSeconds,
            @DefaultValue("10") int maxRejectedCandidatesPerWindow) {

        /** The bounds, refused at startup when a manifest would not accept them. */
        TemplateBounds bounds() {
            return new TemplateBounds(caseWallTimeoutSeconds, maxRejectedCandidatesPerWindow);
        }
    }
}
