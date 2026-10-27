package io.github.temporalrift.workbench.experiment.infrastructure.config;

import java.time.Clock;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.experiment.BaselineRulesCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.LaneBundleCatalog;
import io.github.temporalrift.workbench.experiment.application.command.CreateExperimentCommandHandler;
import io.github.temporalrift.workbench.experiment.application.command.ExperimentFactoryService;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.GetExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.GetVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListExperimentTemplatesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListExperimentsUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.ListLaneBundlesUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewExperimentUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewMatrixUseCase;
import io.github.temporalrift.workbench.experiment.application.port.in.PreviewVariantRulesUseCase;
import io.github.temporalrift.workbench.experiment.application.query.ExperimentCatalogQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.GetExperimentQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.GetVariantRulesQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.ListExperimentTemplatesQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.ListExperimentsQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.ListLaneBundlesQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.ParameterSpaceQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.PreviewExperimentQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.PreviewMatrixQueryHandler;
import io.github.temporalrift.workbench.experiment.application.query.PreviewVariantRulesQueryHandler;
import io.github.temporalrift.workbench.experiment.domain.port.out.ExperimentRepository;
import io.github.temporalrift.workbench.experiment.domain.port.out.IdempotencyStore;
import io.github.temporalrift.workbench.experiment.domain.port.out.ParameterSpaces;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyArtifacts;
import io.github.temporalrift.workbench.experiment.domain.port.out.PolicyReferenceVerifier;
import io.github.temporalrift.workbench.experiment.domain.rules.VariantRules;
import io.github.temporalrift.workbench.experiment.domain.template.TemplateBounds;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.configuration.ConfiguredParameterSpaces;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.ExperimentBaselineJpaRepository;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.ExperimentJpaRepository;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.ExperimentRepositoryAdapter;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.IdempotencyKeyJpaRepository;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.persistence.IdempotencyStoreAdapter;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyArtifactsAdapter;
import io.github.temporalrift.workbench.experiment.infrastructure.adapter.out.policy.PolicyReferenceVerifierAdapter;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.out.persistence.InsertOnce;

@Configuration
@EnableConfigurationProperties(ExperimentProperties.class)
public class ExperimentConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    ExperimentRepository experimentRepository(
            ExperimentJpaRepository repository, ExperimentBaselineJpaRepository baselines) {
        return new ExperimentRepositoryAdapter(repository, baselines);
    }

    @Bean
    IdempotencyStore idempotencyStore(IdempotencyKeyJpaRepository repository, InsertOnce insertOnce) {
        return new IdempotencyStoreAdapter(repository, insertOnce);
    }

    @Bean
    PolicyReferenceVerifier policyReferenceVerifier(PolicyCatalog catalog) {
        return new PolicyReferenceVerifierAdapter(catalog);
    }

    @Bean
    PolicyArtifacts policyArtifacts(PolicyCatalog catalog) {
        return new PolicyArtifactsAdapter(catalog);
    }

    @Bean
    TemplateBounds templateBounds(ExperimentProperties properties) {
        return properties.templates().bounds();
    }

    @Bean
    ListLaneBundlesUseCase listLaneBundlesUseCase(LaneBundleCatalog bundles) {
        return new ListLaneBundlesQueryHandler(bundles);
    }

    @Bean
    ListExperimentTemplatesUseCase listExperimentTemplatesUseCase(
            LaneBundleCatalog bundles, PolicyArtifacts policies, TemplateBounds bounds) {
        return new ListExperimentTemplatesQueryHandler(bundles, policies, bounds);
    }

    @Bean
    ParameterSpaces parameterSpaces(ObjectMapper objectMapper) {
        return new ConfiguredParameterSpaces(objectMapper);
    }

    @Bean
    VariantRules variantRules(ParameterSpaces spaces, BaselineRulesCatalog baselines) {
        return new VariantRules(spaces, baselines);
    }

    @Bean
    ParameterSpaceQueryHandler parameterSpaceQueryHandler(ParameterSpaces spaces) {
        return new ParameterSpaceQueryHandler(spaces);
    }

    @Bean
    PreviewVariantRulesUseCase previewVariantRulesUseCase(VariantRules variantRules) {
        return new PreviewVariantRulesQueryHandler(variantRules);
    }

    @Bean
    GetVariantRulesUseCase getVariantRulesUseCase(ExperimentRepository experiments, VariantRules variantRules) {
        return new GetVariantRulesQueryHandler(experiments, variantRules);
    }

    @Bean
    CreateExperimentUseCase createExperimentUseCase(
            ExperimentRepository experiments,
            IdempotencyStore idempotency,
            Clock clock,
            PolicyReferenceVerifier policyVerifier,
            VariantRules variantRules) {
        return new CreateExperimentCommandHandler(experiments, idempotency, clock, policyVerifier, variantRules);
    }

    @Bean
    ExperimentFactory experimentFactory(PreviewVariantRulesUseCase preview, CreateExperimentUseCase create) {
        return new ExperimentFactoryService(preview, create);
    }

    @Bean
    PreviewMatrixUseCase previewMatrixUseCase(PolicyReferenceVerifier policyVerifier) {
        return new PreviewMatrixQueryHandler(policyVerifier);
    }

    @Bean
    PreviewExperimentUseCase previewExperimentUseCase(
            PolicyReferenceVerifier policyVerifier, VariantRules variantRules) {
        return new PreviewExperimentQueryHandler(policyVerifier, variantRules);
    }

    @Bean
    GetExperimentUseCase getExperimentUseCase(ExperimentRepository experiments) {
        return new GetExperimentQueryHandler(experiments);
    }

    @Bean
    ListExperimentsUseCase listExperimentsUseCase(ExperimentRepository experiments) {
        return new ListExperimentsQueryHandler(experiments);
    }

    @Bean
    ExperimentCatalog experimentCatalog(ExperimentRepository experiments, GetVariantRulesUseCase variantRules) {
        return new ExperimentCatalogQueryHandler(experiments, variantRules);
    }
}
