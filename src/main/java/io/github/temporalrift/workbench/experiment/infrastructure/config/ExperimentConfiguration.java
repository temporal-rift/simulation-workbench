package io.github.temporalrift.workbench.experiment.infrastructure.config;

import java.time.Clock;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.application.command.CreateExperimentCommandHandler;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewMatrixUseCase;
import io.github.temporalrift.workbench.experiment.application.query.ExperimentCatalogQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.PreviewMatrixQueryHandler;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.ExperimentJpaRepository;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.ExperimentRepositoryAdapter;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.IdempotencyKeyJpaRepository;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.IdempotencyStoreAdapter;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyReferenceVerifierAdapter;
import io.github.temporalrift.workbench.policy.PolicyCatalog;

@Configuration
public class ExperimentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ExperimentRepository experimentRepository(ExperimentJpaRepository repository) {
        return new ExperimentRepositoryAdapter(repository);
    }

    @Bean
    IdempotencyStore idempotencyStore(IdempotencyKeyJpaRepository repository) {
        return new IdempotencyStoreAdapter(repository);
    }

    @Bean
    PolicyReferenceVerifier policyReferenceVerifier(PolicyCatalog catalog) {
        return new PolicyReferenceVerifierAdapter(catalog);
    }

    @Bean
    CreateExperimentUseCase createExperimentUseCase(
            ExperimentRepository experiments,
            IdempotencyStore idempotency,
            Clock clock,
            PolicyReferenceVerifier policyVerifier) {
        return new CreateExperimentCommandHandler(experiments, idempotency, clock, policyVerifier);
    }

    @Bean
    PreviewMatrixUseCase previewMatrixUseCase(PolicyReferenceVerifier policyVerifier) {
        return new PreviewMatrixQueryHandler(policyVerifier);
    }

    @Bean
    ExperimentCatalog experimentCatalog(ExperimentRepository experiments, ObjectMapper objectMapper) {
        return new ExperimentCatalogQueryHandler(experiments, objectMapper);
    }
}
