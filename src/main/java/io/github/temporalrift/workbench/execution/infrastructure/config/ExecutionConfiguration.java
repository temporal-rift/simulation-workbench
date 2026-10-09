package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.RunCatalog;
import io.github.temporalrift.workbench.execution.application.command.CancelRunCommandHandler;
import io.github.temporalrift.workbench.execution.application.command.CaseDriver;
import io.github.temporalrift.workbench.execution.application.command.ExecutionSettings;
import io.github.temporalrift.workbench.execution.application.command.ReproduceCaseCommandHandler;
import io.github.temporalrift.workbench.execution.application.command.ReproductionRunner;
import io.github.temporalrift.workbench.execution.application.command.ReproductionSources;
import io.github.temporalrift.workbench.execution.application.command.ResumeRunCommandHandler;
import io.github.temporalrift.workbench.execution.application.command.RunBatchService;
import io.github.temporalrift.workbench.execution.application.command.StartRunCommandHandler;
import io.github.temporalrift.workbench.execution.application.port.in.CancelRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseReplayUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.GetRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.application.query.GetCaseQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.GetCaseReplayQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.GetRunQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.RunCatalogQueryHandler;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.in.scheduler.BatchWorker;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment.ExperimentSourceAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka.KafkaGameEventObservers;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ConfiguredLanePool;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.GameEventObservers;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneServices;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RestApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.CaseLedgerAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.CommandLedgerAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.EvidenceLedgerAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.persistence.ReproductionRepositoryAdapter;
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
    EvidenceLedger evidenceLedger(JdbcTemplate jdbc, TransactionTemplate transactions, Clock clock) {
        return new EvidenceLedgerAdapter(jdbc, transactions, clock);
    }

    @Bean
    ReproductionRepository reproductionRepository(
            JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        return new ReproductionRepositoryAdapter(jdbc, transactions, objectMapper);
    }

    @Bean
    GameEventObservers gameEventObservers(
            KafkaProperties kafka,
            KafkaConnectionDetails connection,
            EvidenceLedger evidence,
            ObjectMapper objectMapper) {
        return new KafkaGameEventObservers(() -> consumerProperties(kafka, connection), evidence, objectMapper);
    }

    /** The Kafka client settings of the workbench, with the broker its connection details name. */
    private static Map<String, Object> consumerProperties(KafkaProperties kafka, KafkaConnectionDetails connection) {
        var properties = new HashMap<String, Object>(kafka.buildConsumerProperties());
        var consumer = connection.getConsumer();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, consumer.getBootstrapServers());
        if (consumer.getSecurityProtocol() != null) {
            properties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, consumer.getSecurityProtocol());
        }
        return properties;
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
    LaneProvider laneProvider(
            ExecutionProperties properties,
            ApiClients clients,
            CommandLedger ledger,
            EvidenceLedger evidence,
            GameEventObservers observers,
            Clock clock) {
        List<LaneEndpoints> lanes = properties.lanes().stream()
                .map(lane -> new LaneEndpoints(
                        lane.id(),
                        lane.gameServiceUrl(),
                        lane.timelineServiceUrl(),
                        lane.readServiceUrl(),
                        lane.operatorToken(),
                        lane.gameEventsTopic(),
                        lane.timelineEventsTopic(),
                        lane.bots().stream()
                                .map(bot -> new LaneEndpoints.BotIdentity(bot.playerId(), bot.token()))
                                .toList()))
                .toList();
        var barrier = new LaneEndpoints.Barrier(
                properties.barrier().pollInterval(),
                properties.barrier().stablePolls(),
                properties.barrier().maxPolls());
        return new ConfiguredLanePool(
                lanes, clients, new LaneServices(ledger, evidence, observers), clock, barrier, Sleeper.thread());
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
    GetCaseReplayUseCase getCaseReplayUseCase(
            RunRepository runs, ExperimentSource experiments, EvidenceLedger evidence) {
        return new GetCaseReplayQueryHandler(runs, experiments, evidence);
    }

    @Bean
    RunCatalog runCatalog(RunRepository runs, EvidenceLedger evidence) {
        return new RunCatalogQueryHandler(runs, evidence);
    }

    @Bean
    ReproductionSources reproductionSources(RunRepository runs, ExperimentSource experiments, EvidenceLedger evidence) {
        return new ReproductionSources(runs, experiments, evidence);
    }

    @Bean
    ReproduceCaseUseCase reproduceCaseUseCase(
            ReproductionSources sources, ReproductionRepository reproductions, Clock clock) {
        return new ReproduceCaseCommandHandler(sources, reproductions, clock);
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
            EvidenceLedger evidence,
            Clock clock,
            ExecutionSettings settings) {
        return new CaseDriver(decisions, commands, cases, evidence, clock, settings);
    }

    @Bean
    RunReproductionUseCase runReproductionUseCase(
            ReproductionRepository reproductions,
            ReproductionSources sources,
            LaneProvider lanes,
            CaseDriver driver,
            Clock clock,
            ExecutionSettings settings) {
        return new ReproductionRunner(reproductions, sources, lanes, driver, clock, settings);
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
    BatchWorker batchWorker(
            RunBatchUseCase batch, RunReproductionUseCase reproductions, ExecutionProperties properties) {
        return new BatchWorker(batch, reproductions, properties.effectiveWorkerThreads(), properties.pollInterval());
    }
}
