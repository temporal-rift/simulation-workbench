package io.github.temporalrift.workbench.shared.infrastructure.config;

import java.util.LinkedHashSet;
import java.util.Set;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;

@Configuration
class JacksonConfig {

    /**
     * The generated models read unique arrays as sets. Their default hash order would reorder what a designer
     * typed, and for enums would differ between runs, so a manifest's seeds and faction sets keep their order.
     */
    @Bean
    JsonMapperBuilderCustomizer uniqueArraysKeepTheirOrder() {
        return builder -> builder.addModule(new SimpleModule().addAbstractTypeMapping(Set.class, LinkedHashSet.class));
    }
}
