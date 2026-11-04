package io.github.temporalrift.workbench.search.infrastructure.config;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Campaign;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignEvaluation;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.CampaignLogEntry;

/**
 * Every field of the campaign representations is required by the published contract, including the nullable ones (a
 * campaign confirmation, failure and timestamps, a log entry evaluation, reason and evidence, and an evaluation score),
 * so the service-wide non-null inclusion must not drop them.
 */
@Configuration
public class SearchApiJsonConfiguration {

    @Bean
    JsonMapperBuilderCustomizer campaignApiKeepsNullableFields() {
        var always = JsonInclude.Value.construct(JsonInclude.Include.ALWAYS, JsonInclude.Include.ALWAYS);
        return builder -> List.of(Campaign.class, CampaignLogEntry.class, CampaignEvaluation.class)
                .forEach(type -> builder.withConfigOverride(type, override -> override.setInclude(always)));
    }
}
