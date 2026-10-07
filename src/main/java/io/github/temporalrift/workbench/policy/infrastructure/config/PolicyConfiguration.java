package io.github.temporalrift.workbench.policy.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;

@Configuration
public class PolicyConfiguration {

    @Bean
    PolicyCatalog policyCatalog() {
        return new BaselinePolicyCatalog();
    }
}
