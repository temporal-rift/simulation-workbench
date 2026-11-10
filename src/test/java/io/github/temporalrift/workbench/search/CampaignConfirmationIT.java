package io.github.temporalrift.workbench.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.analysis.RunConfirmation;
import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentFactory;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.search.application.command.CampaignProgress;
import io.github.temporalrift.workbench.search.application.command.CampaignSettings;
import io.github.temporalrift.workbench.search.application.command.StartConfirmationCommandHandler;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.campaign.SeatTable;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.candidate.ConfirmationSeeds;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

/**
 * A search that ends with a short list, then its confirmation on the scripted lanes: lane 0 stands for the simulator
 * and lane 1 for a deployed lane on images of its own, free to claim any case that fits it. What is exercised is the
 * experiments the confirmation freezes, the runs that play them, the lane every case ran on, and the evaluations the
 * campaign records.
 */
@WorkbenchIntegrationTest
class CampaignConfirmationIT {

    private static final String WORKER = "confirmation-it";
    private static final int STEP_LIMIT = 100_000;
    private static final int CONFIRMATION_SEEDS = 2;
    private static final BundleDigests BASELINE =
            new BundleDigests(VariantRulesFixtures.baseline().digest(), "b".repeat(64));

    @Autowired
    private CampaignRepository campaigns;

    @Autowired
    private CampaignExperiments experiments;

    @Autowired
    private ExperimentFactory factory;

    @Autowired
    private CampaignRuns runs;

    @Autowired
    private CandidateEvaluation realEvaluation;

    @Autowired
    private RunConfirmation confirmation;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private LaneEnvironments environments;

    @Autowired
    private PolicyCatalog policies;

    @Autowired
    private ScriptedLanes lanes;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Clock clock;

    @BeforeEach
    void cleanAndServeTheBaselineOnTheSimulatorAndOneDeployedLane() {
        jdbc.update("DELETE FROM campaign_round");
        jdbc.update("DELETE FROM campaign_log_entry");
        jdbc.update("DELETE FROM campaign_game");
        jdbc.update("DELETE FROM campaign_wave");
        jdbc.update("DELETE FROM campaign_candidate");
        jdbc.update("DELETE FROM campaign");
        jdbc.update("DELETE FROM case_command");
        jdbc.update("DELETE FROM case_attempt");
        jdbc.update("DELETE FROM run_case");
        jdbc.update("DELETE FROM run_command");
        jdbc.update("DELETE FROM run");
        lanes.reset();
        lanes.serveDeployedOnOwnImages(BASELINE, BASELINE);
    }

    @Test
    void aShortListIsConfirmedOnFreshSeedsOnTheSimulatorAndTheEvaluationsAreRecorded() {
        var evaluation = new ScriptedSearch(realEvaluation, uniformTable());
        var progress = progress(evaluation);
        var id = start();
        drive(progress, () -> state(id) == CampaignState.SEARCH_COMPLETED);
        var searched = campaigns.find(id).orElseThrow().campaign();
        assertThat(searched.shortList()).isNotEmpty();

        var started = handler(evaluation).handle(id, UUID.randomUUID());

        assertThat(started.state()).isEqualTo(CampaignState.CONFIRMING);
        var subjects = started.confirmation().runs();
        assertThat(subjects).extracting(Confirmation.Run::subject).containsExactlyElementsOf(searched.shortList());
        assertThat(subjects.stream().map(Confirmation.Run::experimentId)).doesNotHaveDuplicates();

        drive(progress, () -> state(id) == CampaignState.COMPLETED);

        var completed = campaigns.find(id).orElseThrow().campaign();
        assertThat(completed.finishedAt()).isNotNull();
        assertThat(completed.confirmation().seedCount()).isEqualTo(CONFIRMATION_SEEDS);
        assertThat(completed.confirmation().runs()).allSatisfy(run -> {
            assertThat(run.evaluation()).isNotNull();
            var expected = confirmation.confirm(1, run.runId(), run.subject());
            assertThat(run.evaluation().verdict()).isEqualTo(expected.verdict());
            assertThat(run.evaluation().score()).isEqualTo(expected.score());
        });
        var confirmationSeeds = new ConfirmationSeeds("9").seeds(CONFIRMATION_SEEDS);
        assertThat(campaigns.loggedAmong(id, confirmationSeeds)).isEmpty();
        completed.confirmation().runs().forEach(run -> {
            assertThat(seedsOf(run.runId())).containsExactlyInAnyOrderElementsOf(confirmationSeeds);
            assertThat(lanesOf(run.runId()))
                    .as("every case played on the simulator and none on the deployed lane")
                    .containsExactly("lane-0");
        });
    }

    @Test
    void aCriterionVersionTwoConfirmationOfOneCandidateIs104SeedsAnd17160CasesOverThreeTables() {
        var id = searchedCampaign(2, List.of(3, 4, 5), "c-0000");

        var started = handler(realEvaluation).handle(id, UUID.randomUUID());

        assertThat(started.confirmation().seedCount()).isEqualTo(104);
        assertThat(started.confirmation().runs()).singleElement().satisfies(run -> {
            assertThat(run.subject()).isEqualTo("c-0000");
            assertThat(casesOf(run.runId())).isEqualTo(17_160);
            assertThat(seedsOf(run.runId())).hasSize(104);
            assertThat(tablesOf(run.runId()))
                    .containsExactlyInAnyOrder("faction-greedy-v1", "random-v1", "random-seat-vs-faction-greedy-v1");
        });
    }

    @Test
    void aCriterionVersionOneConfirmationIs100SeedsOverItsTwoTables() {
        var id = searchedCampaign(1, List.of(3, 4, 5), "c-0000");

        var started = handler(realEvaluation).handle(id, UUID.randomUUID());

        assertThat(started.confirmation().seedCount()).isEqualTo(100);
        var run = started.confirmation().runs().getFirst();
        assertThat(seedsOf(run.runId())).hasSize(100);
        assertThat(casesOf(run.runId())).isEqualTo(11_000);
        assertThat(tablesOf(run.runId())).containsExactlyInAnyOrder("faction-greedy-v1", "random-v1");
    }

    @Test
    void theSecondRunStartsNoCaseBeforeTheFirstHasCompleted() {
        var evaluation = new ScriptedSearch(realEvaluation, uniformTable());
        var progress = progress(evaluation);
        var id = searchedCampaign(1, List.of(3), "c-0000", "c-0001");
        var started =
                handler(evaluation).handle(id, UUID.randomUUID()).confirmation().runs();
        var first = started.get(0).runId();
        var second = started.get(1).runId();

        drive(progress, () -> state(id) == CampaignState.COMPLETED, () -> {
            if (unfinishedCases(first) > 0) {
                assertThat(attemptsOf(second)).isZero();
                assertThat(concurrencyOf(second)).as("the second run is held").isZero();
            }
        });

        assertThat(attemptsOf(second)).isEqualTo(casesOf(second));
    }

    @Test
    void aRunInterruptedByARestartIsResumedWithoutAClientCallAndPlaysNoFinishedCaseAgain() {
        var evaluation = new ScriptedSearch(realEvaluation, uniformTable());
        var progress = progress(evaluation);
        var id = searchedCampaign(1, List.of(3), "c-0000");
        var runId = handler(evaluation)
                .handle(id, UUID.randomUUID())
                .confirmation()
                .runs()
                .getFirst()
                .runId();
        batch.maintain();
        for (var done = 0; done < 10; done++) {
            assertThat(batch.runNextCase(WORKER)).isTrue();
        }

        batch.recoverAfterRestart();
        assertThat(jdbc.queryForObject("SELECT state FROM run WHERE run_id = ?", String.class, runId))
                .isEqualTo("INTERRUPTED");

        drive(progress, () -> state(id) == CampaignState.COMPLETED);

        assertThat(attemptsOf(runId)).as("every case played exactly once").isEqualTo(casesOf(runId));
    }

    @Test
    void aStoredConfirmationWithABaselineRunCompletesWithThatRunsEvaluationAfterARestart() {
        var evaluation = new ScriptedSearch(realEvaluation, uniformTable());
        var id = searchedCampaign(1, List.of(3), "c-0000");
        var stored = campaigns.find(id).orElseThrow();
        var seeds = new ConfirmationSeeds("9").seeds(CONFIRMATION_SEEDS);
        var tables = realEvaluation.confirmationTables(1);
        var recorded = new ArrayList<Confirmation.Run>();
        var onTheDeployedLane = true;
        for (var subject : List.of("baseline", "c-0000")) {
            var experiment = experiments.freezeConfirmation(
                    UUID.randomUUID(),
                    stored.campaign().definition(),
                    candidate(stored.campaign().definition(), subject, 0),
                    seeds,
                    tables);
            // A run recorded back then pins the builds of the deployed lane, not those of the simulator.
            var played = onTheDeployedLane ? pinnedToTheDeployedLane(experiment) : experiment;
            onTheDeployedLane = false;
            recorded.add(new Confirmation.Run(subject, played, runs.startHeld(played, UUID.randomUUID()), null));
        }
        var confirming = stored.campaign().startConfirmation(new Confirmation(CONFIRMATION_SEEDS, recorded));
        assertThat(campaigns.apply(CampaignChange.of(stored, confirming, clock.instant())))
                .isTrue();

        batch.recoverAfterRestart();
        drive(progress(evaluation), () -> state(id) == CampaignState.COMPLETED);

        var completed = campaigns.find(id).orElseThrow().campaign().confirmation();
        assertThat(completed.runs()).extracting(Confirmation.Run::subject).containsExactly("baseline", "c-0000");
        var baseline = completed.runs().getFirst();
        assertThat(lanesOf(baseline.runId())).containsExactly("lane-1");
        assertThat(lanesOf(completed.runs().getLast().runId())).containsExactly("lane-0");
        assertThat(baseline.evaluation().verdict())
                .isEqualTo(confirmation.confirm(1, baseline.runId(), "baseline").verdict());
    }

    @Test
    void freezingAndStartingAgainWithTheSameKeysReturnsTheSameExperimentAndRun() {
        var id = searchedCampaign(1, List.of(3), "c-0000");
        var definition = campaigns.find(id).orElseThrow().campaign().definition();
        var seeds = new ConfirmationSeeds("9").seeds(CONFIRMATION_SEEDS);
        var tables = realEvaluation.confirmationTables(1);
        var candidate = candidate(definition, "c-0000", 0);
        var experimentKey = UUID.randomUUID();
        var runKey = UUID.randomUUID();

        var experiment = experiments.freezeConfirmation(experimentKey, definition, candidate, seeds, tables);
        var run = runs.startHeld(experiment, runKey);

        assertThat(experiments.freezeConfirmation(experimentKey, definition, candidate, seeds, tables))
                .isEqualTo(experiment);
        assertThat(runs.startHeld(experiment, runKey)).isEqualTo(run);
    }

    /** The experiment frozen again with the builds of the deployed lane that is build-equivalent to the simulator. */
    private UUID pinnedToTheDeployedLane(UUID experimentId) {
        var lane = environments.equivalentDeployedLane(3).orElseThrow().bundle();
        var mapper = JsonMapper.builder().build();
        var manifest = (ObjectNode) mapper.readTree(jdbc.queryForObject(
                "SELECT manifest_json FROM experiment WHERE experiment_id = ?", String.class, experimentId));
        var services = manifest.putObject("services");
        Map.of(
                        "gameService",
                        lane.gameService(),
                        "timelineService",
                        lane.timelineService(),
                        "readService",
                        lane.readService())
                .forEach((name, build) -> services.putObject(name)
                        .put("imageDigest", build.imageDigest())
                        .put("sourceRevision", build.sourceRevision()));
        return factory.freeze(UUID.randomUUID(), manifest);
    }

    private CampaignProgress progress(CandidateEvaluation evaluation) {
        return new CampaignProgress(campaigns, experiments, runs, evaluation, new CampaignSettings(50_000), clock);
    }

    private StartConfirmationCommandHandler handler(CandidateEvaluation evaluation) {
        return new StartConfirmationCommandHandler(campaigns, experiments, runs, evaluation, clock);
    }

    private int concurrencyOf(UUID runId) {
        return jdbc.queryForObject("SELECT concurrency FROM run WHERE run_id = ?", Integer.class, runId);
    }

    private int casesOf(UUID runId) {
        return jdbc.queryForObject("SELECT count(*) FROM run_case WHERE run_id = ?", Integer.class, runId);
    }

    private int unfinishedCases(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM run_case WHERE run_id = ? AND state IN ('PENDING', 'RUNNING')",
                Integer.class,
                runId);
    }

    private int attemptsOf(UUID runId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM case_attempt a JOIN run_case c ON c.case_id = a.case_id WHERE c.run_id = ?",
                Integer.class,
                runId);
    }

    private List<String> tablesOf(UUID runId) {
        return jdbc.queryForList("SELECT DISTINCT table_id FROM run_case WHERE run_id = ?", String.class, runId);
    }

    private List<String> lanesOf(UUID runId) {
        return jdbc.queryForList(
                "SELECT DISTINCT a.lane_id FROM case_attempt a JOIN run_case c ON c.case_id = a.case_id"
                        + " WHERE c.run_id = ? ORDER BY a.lane_id",
                String.class,
                runId);
    }

    private List<String> seedsOf(UUID runId) {
        return jdbc.queryForList("SELECT DISTINCT seed FROM run_case WHERE run_id = ?", String.class, runId);
    }

    private CampaignState state(UUID id) {
        return campaigns.find(id).orElseThrow().campaign().state();
    }

    private UUID start() {
        var campaign = Campaign.queued(UUID.randomUUID(), definition(1, List.of(3)), 4, clock.instant());
        campaigns.create(UUID.randomUUID(), "h".repeat(64), campaign, clock.instant());
        return campaign.campaignId();
    }

    /** A campaign whose search is complete with the given short list, each candidate on rules of its own. */
    private UUID searchedCampaign(int criterionVersion, List<Integer> playerCounts, String... shortList) {
        var now = clock.instant();
        var queued = Campaign.queued(UUID.randomUUID(), definition(criterionVersion, playerCounts), 4, now);
        campaigns.create(UUID.randomUUID(), "h".repeat(64), queued, now);
        var candidates = new ArrayList<Candidate>();
        for (var index = 0; index < shortList.length; index++) {
            candidates.add(candidate(queued.definition(), shortList[index], index));
        }
        campaigns.insertCandidates(queued.campaignId(), candidates);
        var searched = queued.start(now).completeSearch(List.of(shortList));
        assertThat(campaigns.apply(
                        CampaignChange.of(campaigns.find(queued.campaignId()).orElseThrow(), searched, now)))
                .isTrue();
        return queued.campaignId();
    }

    /** A candidate on the baseline rules, or for {@code index} above 0, one step away on an axis of its own. */
    private Candidate candidate(CampaignDefinition definition, String id, int index) {
        var validation = experiments.validation(definition);
        if (index == 0) {
            var baseline = validation.validate(new TreeMap<>()).orElseThrow();
            return new Candidate(id, 0, List.of(0), baseline.overrides(), baseline.effectiveRulesDigest(), 0);
        }
        var validated = experiments.grid(definition).axes().stream()
                .skip(index - 1L)
                .flatMap(axis -> Stream.of(
                                axis.baseline().add(axis.step()),
                                axis.baseline().subtract(axis.step()))
                        .map(value -> validation.validate(new TreeMap<>(Map.of(axis.key(), value)))))
                .flatMap(Optional::stream)
                .findFirst()
                .orElseThrow();
        return new Candidate(id, 0, List.of(index), validated.overrides(), validated.effectiveRulesDigest(), 0);
    }

    private CampaignDefinition definition(int criterionVersion, List<Integer> playerCounts) {
        var simulator = environments.simulator().orElseThrow();
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact")
                .put("digest", simulator.rulesArtifact().digest());
        baseline.putObject("contentArtifact")
                .put("digest", simulator.contentArtifact().digest());
        var policy = policies.list().getFirst();
        var node = mapper.createObjectNode()
                .put("id", policy.id())
                .put("version", policy.version())
                .put("artifactDigest", policy.artifactDigest());
        node.putObject("parameters");
        return new CampaignDefinition(
                "it confirmation",
                baseline,
                1,
                criterionVersion,
                mapper.createArrayNode().add(node),
                playerCounts,
                CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                "7",
                "9",
                new SearchAlgorithm(2, 2, 2, 1));
    }

    private void drive(CampaignProgress progress, BooleanSupplier done) {
        drive(progress, done, () -> {});
    }

    /**
     * Moves the campaign and the execution workers along until {@code done} holds, checking {@code everyStep}. A step
     * that only allows or resumes a run changes nothing the workers see until the next one, so one idle step is not
     * the end: two in a row are.
     */
    private void drive(CampaignProgress progress, BooleanSupplier done, Runnable everyStep) {
        var idle = 0;
        for (var step = 0; step < STEP_LIMIT && !done.getAsBoolean() && idle < 2; step++) {
            batch.maintain();
            var advanced = progress.advance();
            if (done.getAsBoolean()) {
                break;
            }
            var played = batch.runNextCase(WORKER);
            everyStep.run();
            idle = advanced || played ? 0 : idle + 1;
        }
        assertThat(done.getAsBoolean())
                .as("the campaign reached the awaited point")
                .isTrue();
    }

    /** One table on which the first catalogued policy plays every seat. */
    private SeatTable uniformTable() {
        var policy = policies.list().getFirst();
        var major = policy.version().substring(0, policy.version().indexOf('.'));
        return new SeatTable("it-table", java.util.Collections.nCopies(5, new PolicyRef(policy.id(), major)));
    }

    /**
     * The real evaluation for confirmation, with a small confirmation sample so the test stays quick, and a scripted
     * screening in which only the first candidate survives.
     */
    private record ScriptedSearch(CandidateEvaluation real, SeatTable table) implements CandidateEvaluation {

        @Override
        public boolean servesCriterion(int criterionVersion) {
            return real.servesCriterion(criterionVersion);
        }

        @Override
        public int confirmationSeedCount(int criterionVersion) {
            return CONFIRMATION_SEEDS;
        }

        @Override
        public List<SeatTable> screeningTables(int criterionVersion) {
            return List.of(table);
        }

        @Override
        public List<PolicyRef> confirmationPolicies(int criterionVersion) {
            return real.confirmationPolicies(criterionVersion);
        }

        @Override
        public List<SeatTable> confirmationTables(int criterionVersion) {
            return real.confirmationTables(criterionVersion);
        }

        @Override
        public Evaluation confirm(int criterionVersion, UUID runId, String variantLabel) {
            return real.confirm(criterionVersion, runId, variantLabel);
        }

        @Override
        public Screening screen(int criterionVersion, List<CandidateGames> candidates) {
            var evaluations = new java.util.HashMap<String, Evaluation>();
            candidates.forEach(candidate -> {
                var first = candidate.candidateId().equals("c-0000");
                evaluations.put(
                        candidate.candidateId(),
                        new Evaluation(
                                first ? "CONFIRMED" : "REJECTED",
                                first ? 1.0 : 9.0,
                                first ? List.of() : List.of("g1")));
            });
            return new Screening(evaluations, java.util.Map.of());
        }
    }
}
