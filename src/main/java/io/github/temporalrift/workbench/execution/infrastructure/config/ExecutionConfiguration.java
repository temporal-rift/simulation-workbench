package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.time.Clock;
import java.util.List;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.application.command.CancelRunCommandHandler;
import io.github.temporalrift.workbench.execution.application.command.CaseDriver;
import io.github.temporalrift.workbench.execution.application.command.ExecutionSettings;
import io.github.temporalrift.workbench.execution.application.command.ResumeRunCommandHandler;
import io.github.temporalrift.workbench.execution.application.command.RunBatchService;
import io.github.temporalrift.workbench.execution.application.command.StartRunCommandHandler;
import io.github.temporalrift.workbench.execution.application.port.in.CancelRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.application.query.GetCaseQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.GetRunQueryHandler;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.in.scheduler.BatchWorker;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment.ExperimentSourceAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ConfiguredLanePool;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RestApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.CaseLedgerAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.CommandLedgerAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.RunRepositoryAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.policy.DecisionRuntimeAdapter;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.policy.PolicyRuntime;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {

    @Bean
    RunRepository runRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        return new RunRepositoryAdapter(jdbc, transactions, objectMapper);
    }

    @Bean
    CaseLedger caseLedger(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        return new CaseLedgerAdapter(jdbc, transactions, objectMapper);
    }

    @Bean
    CommandLedger commandLedger(JdbcTemplate jdbc, TransactionTemplate transactions) {
        return new CommandLedgerAdapter(jdbc, transactions);
    }

    @Bean
    ExperimentSource experimentSource(ExperimentCatalog catalog) {
        return new ExperimentSourceAdapter(catalog);
    }

    @Bean
    DecisionRuntime decisionRuntime(PolicyRuntime runtime) {
        return new DecisionRuntimeAdapter(runtime);
    }

    @Bean
    ApiClients apiClients(ExecutionProperties properties) {
        return new RestApiClients(
                properties.client().connectTimeout(), properties.client().readTimeout());
    }

    @Bean
    LaneProvider laneProvider(ExecutionProperties properties, ApiClients clients, CommandLedger ledger, Clock clock) {
        List<LaneEndpoints> lanes = properties.lanes().stream()
                .map(lane -> new LaneEndpoints(
                        lane.id(),
                        lane.gameServiceUrl(),
                        lane.timelineServiceUrl(),
                        lane.readServiceUrl(),
                        lane.operatorToken(),
                        lane.bots().stream()
                                .map(bot -> new LaneEndpoints.BotIdentity(bot.playerId(), bot.token()))
                                .toList()))
                .toList();
        var barrier = new LaneEndpoints.Barrier(
                properties.barrier().pollInterval(),
                properties.barrier().stablePolls(),
                properties.barrier().maxPolls());
        return new ConfiguredLanePool(lanes, clients, ledger, clock, barrier, Sleeper.thread());
    }

    @Bean
    ExecutionSettings executionSettings(ExecutionProperties properties) {
        return new ExecutionSettings(
                properties.lease(), properties.heartbeat(), properties.maxAttemptsPerCase(), properties.logicalEpoch());
    }

    @Bean
    StartRunUseCase startRunUseCase(ExperimentSource experiments, RunRepository runs, Clock clock) {
        return new StartRunCommandHandler(experiments, runs, clock);
    }

    @Bean
    GetRunUseCase getRunUseCase(RunRepository runs) {
        return new GetRunQueryHandler(runs);
    }

    @Bean
    GetCaseUseCase getCaseUseCase(RunRepository runs) {
        return new GetCaseQueryHandler(runs);
    }

    @Bean
    CancelRunUseCase cancelRunUseCase(RunRepository runs, Clock clock) {
        return new CancelRunCommandHandler(runs, clock);
    }

    @Bean
    ResumeRunUseCase resumeRunUseCase(RunRepository runs, Clock clock) {
        return new ResumeRunCommandHandler(runs, clock);
    }

    @Bean
    CaseDriver caseDriver(
            DecisionRuntime decisions,
            CommandLedger commands,
            CaseLedger cases,
            Clock clock,
            ExecutionSettings settings) {
        return new CaseDriver(decisions, commands, cases, clock, settings);
    }

    @Bean
    RunBatchUseCase runBatchUseCase(
            RunRepository runs,
            CaseLedger cases,
            ExperimentSource experiments,
            LaneProvider lanes,
            CaseDriver driver,
            Clock clock,
            ExecutionSettings settings) {
        return new RunBatchService(runs, cases, experiments, lanes, driver, clock, settings);
    }

    @Bean
    @ConditionalOnProperty(name = "workbench.execution.worker-enabled", havingValue = "true", matchIfMissing = true)
    BatchWorker batchWorker(RunBatchUseCase batch, ExecutionProperties properties) {
        return new BatchWorker(batch, properties.effectiveWorkerThreads(), properties.pollInterval());
    }
}
