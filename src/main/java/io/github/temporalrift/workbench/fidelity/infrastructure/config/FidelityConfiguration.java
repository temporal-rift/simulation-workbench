package io.github.temporalrift.workbench.fidelity.infrastructure.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.CaseReproducer;
import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.fidelity.SimulatorFidelityGate;
import io.github.temporalrift.workbench.fidelity.application.command.FidelityManifestFactory;
import io.github.temporalrift.workbench.fidelity.application.command.FidelityProgress;
import io.github.temporalrift.workbench.fidelity.application.command.StartFidelityCheckCommandHandler;
import io.github.temporalrift.workbench.fidelity.application.port.in.AdvanceFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityCasesUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityChecksUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.application.query.GetFidelityCheckQueryHandler;
import io.github.temporalrift.workbench.fidelity.application.query.GetSimulatorFidelityQueryHandler;
import io.github.temporalrift.workbench.fidelity.application.query.ListFidelityCasesQueryHandler;
import io.github.temporalrift.workbench.fidelity.application.query.ListFidelityChecksQueryHandler;
import io.github.temporalrift.workbench.fidelity.domain.EdgeVariants;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityEnvironments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityExperiments;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityPlayback;
import io.github.temporalrift.workbench.fidelity.domain.port.out.FidelityRepository;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.scheduler.FidelityWorker;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.configuration.ConfiguredEdgeVariants;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.execution.FidelityEnvironmentsAdapter;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.execution.FidelityExperimentsAdapter;
import io.github.temporalrift.workbench.fidelity.infrastructure.adapter.out.execution.FidelityPlaybackAdapter;
import io.github.temporalrift.workbench.policy.PolicyCatalog;

@Configuration
@EnableConfigurationProperties(FidelityProperties.class)
public class FidelityConfiguration {

    @Bean
    EdgeVariants edgeVariants(ObjectMapper objectMapper) {
        return ConfiguredEdgeVariants.load(objectMapper);
    }

    @Bean
    FidelityEnvironments fidelityEnvironments(LaneEnvironments lanes) {
        return new FidelityEnvironmentsAdapter(lanes);
    }

    @Bean
    FidelityExperiments fidelityExperiments(ExperimentFactory experiments, PolicyCatalog policies) {
        return new FidelityExperimentsAdapter(experiments, policies);
    }

    @Bean
    FidelityPlayback fidelityPlayback(RunStarter runs, RunCatalog catalog, CaseReproducer reproducer) {
        return new FidelityPlaybackAdapter(runs, catalog, reproducer);
    }

    @Bean
    StartFidelityCheckUseCase startFidelityCheckUseCase(
            FidelityRepository checks,
            FidelityEnvironments environments,
            FidelityExperiments experiments,
            EdgeVariants edgeVariants,
            Clock clock) {
        return new StartFidelityCheckCommandHandler(checks, environments, experiments, edgeVariants, clock);
    }

    @Bean
    GetFidelityCheckUseCase getFidelityCheckUseCase(FidelityRepository checks) {
        return new GetFidelityCheckQueryHandler(checks);
    }

    @Bean
    ListFidelityChecksUseCase listFidelityChecksUseCase(FidelityRepository checks) {
        return new ListFidelityChecksQueryHandler(checks);
    }

    @Bean
    ListFidelityCasesUseCase listFidelityCasesUseCase(FidelityRepository checks) {
        return new ListFidelityCasesQueryHandler(checks);
    }

    @Bean
    GetSimulatorFidelityQueryHandler simulatorFidelity(FidelityRepository checks, FidelityEnvironments environments) {
        return new GetSimulatorFidelityQueryHandler(checks, environments);
    }

    @Bean
    GetSimulatorFidelityUseCase getSimulatorFidelityUseCase(GetSimulatorFidelityQueryHandler handler) {
        return handler;
    }

    @Bean
    SimulatorFidelityGate simulatorFidelityGate(GetSimulatorFidelityQueryHandler handler) {
        return handler;
    }

    @Bean
    AdvanceFidelityUseCase advanceFidelityUseCase(
            FidelityRepository checks,
            FidelityEnvironments environments,
            FidelityExperiments experiments,
            FidelityPlayback playback,
            EdgeVariants edgeVariants,
            FidelityProperties properties,
            ObjectMapper objectMapper,
            Clock clock) {
        var settings = properties.settings();
        return new FidelityProgress(
                checks,
                environments,
                experiments,
                playback,
                new FidelityManifestFactory(objectMapper, settings, edgeVariants.parameterSpaceVersion()),
                settings,
                clock);
    }

    @Bean
    @ConditionalOnProperty(name = "workbench.fidelity.worker-enabled", havingValue = "true", matchIfMissing = true)
    FidelityWorker fidelityWorker(AdvanceFidelityUseCase fidelity, FidelityProperties properties) {
        return new FidelityWorker(fidelity, properties.pollInterval());
    }
}
