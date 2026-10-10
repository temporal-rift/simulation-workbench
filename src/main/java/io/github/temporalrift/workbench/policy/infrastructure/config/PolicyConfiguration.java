package io.github.temporalrift.workbench.policy.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.policy.PolicyRuntime;
import io.github.temporalrift.workbench.policy.application.command.BaselinePolicyRuntime;
import io.github.temporalrift.workbench.policy.application.port.in.ListPoliciesUseCase;
import io.github.temporalrift.workbench.policy.application.query.BaselinePolicyCatalog;
import io.github.temporalrift.workbench.policy.application.query.ListPoliciesQueryHandler;

@Configuration
public class PolicyConfiguration {

    @Bean
    PolicyCatalog policyCatalog() {
        return new BaselinePolicyCatalog();
    }

    @Bean
    ListPoliciesUseCase listPoliciesUseCase(PolicyCatalog catalog) {
        return new ListPoliciesQueryHandler(catalog);
    }

    @Bean
    PolicyRuntime policyRuntime() {
        return new BaselinePolicyRuntime();
    }
}
