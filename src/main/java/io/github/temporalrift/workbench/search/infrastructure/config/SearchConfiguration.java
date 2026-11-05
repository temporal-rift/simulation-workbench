package io.github.temporalrift.workbench.search.infrastructure.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.analysis.CandidateScreening;
import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.RunStarter;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.fidelity.SimulatorFidelityGate;
import io.github.temporalrift.workbench.search.application.command.CampaignProgress;
import io.github.temporalrift.workbench.search.application.command.CancelCampaignCommandHandler;
import io.github.temporalrift.workbench.search.application.command.CreateCampaignCommandHandler;
import io.github.temporalrift.workbench.search.application.command.PauseCampaignCommandHandler;
import io.github.temporalrift.workbench.search.application.command.ResumeCampaignCommandHandler;
import io.github.temporalrift.workbench.search.application.command.SetCampaignParallelismCommandHandler;
import io.github.temporalrift.workbench.search.application.port.in.AdvanceCampaignsUseCase;
import io.github.temporalrift.workbench.search.application.port.in.CancelCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignLogUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ListCampaignsUseCase;
import io.github.temporalrift.workbench.search.application.port.in.PauseCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ResumeCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.SetCampaignParallelismUseCase;
import io.github.temporalrift.workbench.search.application.query.GetCampaignLogQueryHandler;
import io.github.temporalrift.workbench.search.application.query.GetCampaignQueryHandler;
import io.github.temporalrift.workbench.search.application.query.ListCampaignsQueryHandler;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignDefaults;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.search.domain.port.out.FidelityGate;
import io.github.temporalrift.workbench.search.domain.port.out.MasterSeeds;
import io.github.temporalrift.workbench.search.infrastructure.adapter.in.scheduler.CampaignWorker;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.analysis.CandidateEvaluationAdapter;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults.RandomMasterSeeds;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.defaults.ShippedCampaignDefaults;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.execution.CampaignRunsAdapter;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.experiment.CampaignExperimentsAdapter;
import io.github.temporalrift.workbench.search.infrastructure.adapter.out.fidelity.FidelityGateAdapter;

@Configuration
@EnableConfigurationProperties(SearchProperties.class)
public class SearchConfiguration {

    @Bean
    CampaignExperiments campaignExperiments(
            ExperimentFactory experiments,
            LaneEnvironments lanes,
            ObjectMapper objectMapper,
            SearchProperties properties) {
        return new CampaignExperimentsAdapter(experiments, lanes, objectMapper, properties.bounds());
    }

    @Bean
    CampaignRuns campaignRuns(RunStarter runs, RunCatalog catalog) {
        return new CampaignRunsAdapter(runs, catalog);
    }

    @Bean
    CandidateEvaluation candidateEvaluation(CandidateScreening screening) {
        return new CandidateEvaluationAdapter(screening);
    }

    @Bean
    FidelityGate fidelityGate(SimulatorFidelityGate gate) {
        return new FidelityGateAdapter(gate);
    }

    @Bean
    AdvanceCampaignsUseCase advanceCampaignsUseCase(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CampaignRuns runs,
            CandidateEvaluation evaluation,
            FidelityGate gate,
            SearchProperties properties,
            Clock clock) {
        return new CampaignProgress(campaigns, experiments, runs, evaluation, gate, properties.settings(), clock);
    }

    @Bean
    CampaignDefaults campaignDefaults(
            ExperimentFactory experiments, CandidateScreening screening, ObjectMapper objectMapper) {
        return new ShippedCampaignDefaults(experiments, screening, objectMapper);
    }

    @Bean
    MasterSeeds masterSeeds() {
        return new RandomMasterSeeds();
    }

    @Bean
    CreateCampaignUseCase createCampaignUseCase(
            CampaignRepository campaigns,
            CampaignExperiments experiments,
            CandidateEvaluation evaluation,
            CampaignDefaults defaults,
            MasterSeeds masterSeeds,
            Clock clock) {
        return new CreateCampaignCommandHandler(campaigns, experiments, evaluation, defaults, masterSeeds, clock);
    }

    @Bean
    GetCampaignUseCase getCampaignUseCase(CampaignRepository campaigns, Clock clock) {
        return new GetCampaignQueryHandler(campaigns, clock);
    }

    @Bean
    ListCampaignsUseCase listCampaignsUseCase(CampaignRepository campaigns, Clock clock) {
        return new ListCampaignsQueryHandler(campaigns, clock);
    }

    @Bean
    GetCampaignLogUseCase getCampaignLogUseCase(CampaignRepository campaigns) {
        return new GetCampaignLogQueryHandler(campaigns);
    }

    @Bean
    PauseCampaignUseCase pauseCampaignUseCase(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        return new PauseCampaignCommandHandler(campaigns, runs, clock);
    }

    @Bean
    ResumeCampaignUseCase resumeCampaignUseCase(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        return new ResumeCampaignCommandHandler(campaigns, runs, clock);
    }

    @Bean
    CancelCampaignUseCase cancelCampaignUseCase(CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        return new CancelCampaignCommandHandler(campaigns, runs, clock);
    }

    @Bean
    SetCampaignParallelismUseCase setCampaignParallelismUseCase(
            CampaignRepository campaigns, CampaignRuns runs, Clock clock) {
        return new SetCampaignParallelismCommandHandler(campaigns, runs, clock);
    }

    @Bean
    @ConditionalOnProperty(name = "workbench.search.worker-enabled", havingValue = "true", matchIfMissing = true)
    CampaignWorker campaignWorker(AdvanceCampaignsUseCase campaigns, SearchProperties properties) {
        return new CampaignWorker(campaigns, properties.pollInterval());
    }
}
