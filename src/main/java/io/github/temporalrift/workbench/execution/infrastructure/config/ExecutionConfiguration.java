package io.github.temporalrift.workbench.execution.infrastructure.config;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
import io.github.temporalrift.workbench.execution.application.port.in.ListCasesUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ListRunsUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ReproduceCaseUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.ResumeRunUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.application.query.GetCaseQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.GetCaseReplayQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.GetRunQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.LaneBundleCatalogQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.ListCasesQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.ListRunsQueryHandler;
import io.github.temporalrift.workbench.execution.application.query.RunCatalogQueryHandler;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.CommandLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.DecisionRuntime;
import io.github.temporalrift.workbench.execution.domain.port.out.EvidenceLedger;
import io.github.temporalrift.workbench.execution.domain.port.out.ExperimentSource;
import io.github.temporalrift.workbench.execution.domain.port.out.LaneProvider;
import io.github.temporalrift.workbench.execution.domain.port.out.ReproductionRepository;
import io.github.temporalrift.workbench.execution.domain.port.out.RunRepository;
import io.github.temporalrift.workbench.execution.domain.run.BundleProvenance;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.in.scheduler.BatchWorker;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.experiment.ExperimentSourceAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.kafka.KafkaGameEventObservers;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.CachingApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.CompositeLaneProvider;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ConfiguredLanePool;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.GameEventObservers;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.HttpGameSession;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.HttpLaneContexts;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.HttpLaneResets;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneEndpoints;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneRoster;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneServices;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.LaneWaits;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.MeteredLaneContexts;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.MeteredLaneResets;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.ResetScheduler;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.RestApiClients;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.Sleeper;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.metrics.MicrometerAttemptTimings;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.policy.DecisionRuntimeAdapter;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.rules.ConfiguredBaselineRules;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.simulator.SimulatorLanePool;
import io.github.temporalrift.workbench.experiment.BaselineRulesCatalog;
import io.github.temporalrift.workbench.experiment.ExperimentCatalog;
import io.github.temporalrift.workbench.experiment.LaneBundleCatalog;
import io.github.temporalrift.workbench.policy.PolicyRuntime;

@Configuration
@EnableConfigurationProperties(ExecutionProperties.class)
public class ExecutionConfiguration {

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

    /** The baseline documents the lanes and the simulator declare, checked against their declared digests. */
    @Bean
    BaselineRulesCatalog baselineRulesCatalog(
            ExecutionProperties properties, SimulatorProperties simulator, ObjectMapper objectMapper) {
        var declarations = new ArrayList<ConfiguredBaselineRules.Declaration>();
        properties
                .lanes()
                .forEach(lane -> declarations.add(new ConfiguredBaselineRules.Declaration(
                        lane.scope(), lane.rulesArtifactDigest(), lane.rulesDigest(), lane.rulesDocument())));
        if (simulator.enabled()) {
            var bundle = simulator.bundle();
            declarations.add(new ConfiguredBaselineRules.Declaration(
                    "workbench.simulator.bundle.",
                    bundle.rulesArtifactDigest(),
                    bundle.rulesDigest(),
                    bundle.rulesDocument()));
        }
        return new ConfiguredBaselineRules(declarations, objectMapper);
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

    /**
     * Runs lane recoveries off the worker threads. A reset may wait minutes for the deployment to recycle its lane, and
     * a lane has at most one recovery in flight, so one thread per lane keeps a slow lane from delaying the others.
     */
    @Bean(destroyMethod = "shutdownNow")
    ScheduledExecutorService laneResetExecutor(ExecutionProperties properties, SimulatorProperties simulator) {
        return Executors.newScheduledThreadPool(
                Math.max(1, properties.lanes().size() + simulator.slots()),
                Thread.ofVirtual().name("lane-reset-", 0).factory());
    }

    @Bean
    LaneProvider laneProvider(
            ExecutionProperties properties,
            SimulatorProperties simulator,
            ApiClients clients,
            CommandLedger ledger,
            EvidenceLedger evidence,
            GameEventObservers observers,
            ObjectMapper objectMapper,
            Clock clock,
            ScheduledExecutorService laneResetExecutor,
            MeterRegistry registry) {
        var barrier = new LaneEndpoints.Barrier(
                properties.barrier().pollInterval(), properties.barrier().timeout());
        var providers = new ArrayList<LaneProvider>();
        var waits = new LaneWaits(registry);
        var pacing = new HttpGameSession.Pacing(barrier, Sleeper.thread(), waits);
        providers.add(httpLanes(
                properties,
                clients,
                new LaneServices(ledger, evidence, observers),
                clock,
                pacing,
                registry,
                laneResetExecutor));
        if (simulator.enabled()) {
            providers.add(simulatorLanes(
                    simulator,
                    new SimulatorLanePool.Collaborators(
                            clients,
                            ledger,
                            evidence,
                            objectMapper,
                            clock,
                            barrier,
                            Sleeper.thread(),
                            waits,
                            ResetScheduler.on(laneResetExecutor))));
        }
        return providers.size() == 1 ? providers.getFirst() : new CompositeLaneProvider(providers);
    }

    private static LaneProvider httpLanes(
            ExecutionProperties properties,
            ApiClients clients,
            LaneServices services,
            Clock clock,
            HttpGameSession.Pacing pacing,
            MeterRegistry registry,
            ScheduledExecutorService laneResetExecutor) {
        List<LaneEndpoints> lanes = properties.lanes().stream()
                .map(lane -> new LaneEndpoints(
                        lane.id(),
                        lane.gameServiceUrl(),
                        lane.timelineServiceUrl(),
                        lane.readServiceUrl(),
                        lane.operatorToken(),
                        lane.bundle(),
                        lane.provenance(),
                        lane.gameEventsTopic(),
                        lane.timelineEventsTopic(),
                        lane.kafkaBootstrapServers(),
                        lane.resetUrl() == null ? null : URI.create(lane.resetUrl()),
                        lane.bots().stream()
                                .map(bot -> new LaneEndpoints.BotIdentity(bot.playerId(), bot.token()))
                                .toList()))
                .toList();
        var closing = new CachingApiClients(
                new RestApiClients(properties.client().connectTimeout(), properties.resetTimeout()));
        var roster = new LaneRoster(
                lanes.stream().map(LaneEndpoints::member).toList(),
                new MeteredLaneContexts(new HttpLaneContexts(lanes, closing), registry),
                new MeteredLaneResets(
                        new HttpLaneResets(lanes, properties.client().connectTimeout(), properties.resetTimeout()),
                        registry),
                ResetScheduler.on(laneResetExecutor));
        return new ConfiguredLanePool(lanes, roster, clients, services, clock, pacing);
    }

    private static LaneProvider simulatorLanes(
            SimulatorProperties simulator, SimulatorLanePool.Collaborators collaborators) {
        var bundle = simulator.bundle();
        var revisions = bundle.sourceRevisions();
        var workDirectory = simulator.workDirectory() != null
                ? simulator.workDirectory()
                : Path.of(System.getProperty("java.io.tmpdir"), "workbench-simulator");
        return SimulatorLanePool.create(
                new SimulatorLanePool.Deployment(
                        simulator.slots(),
                        simulator.gameServiceJar(),
                        simulator.timelineServiceJar(),
                        simulator.readServiceJar(),
                        simulator.rulesFile(),
                        workDirectory,
                        simulator.effectiveBotPlayerIds(),
                        bundle.digests(),
                        new SimulatorLanePool.Declared(
                                new BundleProvenance.Artifact(bundle.rulesArtifactDigest(), bundle.rulesArtifactName()),
                                new BundleProvenance.Artifact(
                                        bundle.contentArtifactDigest(), bundle.contentArtifactName()),
                                revisions.gameService(),
                                revisions.timelineService(),
                                revisions.readService(),
                                bundle.sortedContracts())),
                collaborators);
    }

    @Bean
    ExecutionSettings executionSettings(ExecutionProperties properties) {
        return new ExecutionSettings(
                properties.lease(), properties.heartbeat(), properties.maxAttemptsPerCase(), properties.logicalEpoch());
    }

    @Bean
    StartRunUseCase startRunUseCase(ExperimentSource experiments, RunRepository runs, LaneProvider lanes, Clock clock) {
        return new StartRunCommandHandler(experiments, runs, lanes, clock);
    }

    @Bean
    GetRunUseCase getRunUseCase(RunRepository runs) {
        return new GetRunQueryHandler(runs);
    }

    @Bean
    ListRunsUseCase listRunsUseCase(RunRepository runs) {
        return new ListRunsQueryHandler(runs);
    }

    @Bean
    ListCasesUseCase listCasesUseCase(RunRepository runs) {
        return new ListCasesQueryHandler(runs);
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
    LaneBundleCatalog laneBundleCatalog(LaneProvider lanes) {
        return new LaneBundleCatalogQueryHandler(lanes);
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
            ExecutionSettings settings,
            MeterRegistry registry) {
        return new RunBatchService(
                runs,
                cases,
                experiments,
                new RunBatchService.Playing(lanes, driver, new MicrometerAttemptTimings(registry)),
                clock,
                settings);
    }

    @Bean
    @ConditionalOnProperty(name = "workbench.execution.worker-enabled", havingValue = "true", matchIfMissing = true)
    BatchWorker batchWorker(
            RunBatchUseCase batch,
            RunReproductionUseCase reproductions,
            ExecutionProperties properties,
            SimulatorProperties simulator) {
        return new BatchWorker(
                batch, reproductions, properties.effectiveWorkerThreads(simulator.slots()), properties.pollInterval());
    }
}
