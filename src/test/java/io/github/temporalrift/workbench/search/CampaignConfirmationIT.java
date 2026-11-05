package io.github.temporalrift.workbench.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;
import io.github.temporalrift.workbench.analysis.RunConfirmation;
import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.search.application.command.CampaignProgress;
import io.github.temporalrift.workbench.search.application.command.CampaignSettings;
import io.github.temporalrift.workbench.search.application.command.StartConfirmationCommandHandler;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.candidate.ConfirmationSeeds;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;

/**
 * A search that ends with a short list, then its confirmation on the scripted lanes: lane 0 stands for the simulator
 * and lane 1 for the deployed lane that is build-equivalent to it. What is exercised is the experiments the
 * confirmation freezes, the runs that play them, the lane every case ran on, and the evaluations the campaign records.
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
    void aShortListIsConfirmedOnFreshSeedsOnTheDeployedLaneAndTheEvaluationsAreRecorded() {
        var evaluation = new ScriptedSearch(realEvaluation);
        var progress = new CampaignProgress(
                campaigns, experiments, runs, evaluation, () -> true, new CampaignSettings(50_000), clock);
        var id = searched(progress);
        var searchedCampaign = campaigns.find(id).orElseThrow().campaign();
        assertThat(searchedCampaign.shortList()).isNotEmpty();
        var searchSimulatorCases = attemptLanes();
        assertThat(searchSimulatorCases)
                .as("the search played on the simulator only")
                .containsExactly("lane-0");

        var started = new StartConfirmationCommandHandler(campaigns, experiments, runs, evaluation).handle(id);

        assertThat(started.state()).isEqualTo(CampaignState.CONFIRMING);
        var subjects = started.confirmation().runs();
        assertThat(subjects)
                .extracting(Confirmation.Run::subject)
                .containsExactlyElementsOf(java.util.stream.Stream.concat(
                                java.util.stream.Stream.of("baseline"), searchedCampaign.shortList().stream())
                        .toList());
        assertThat(subjects.stream().map(Confirmation.Run::experimentId)).doesNotHaveDuplicates();

        var runIds = subjects.stream().map(Confirmation.Run::runId).toList();
        var mostRunning = 0;
        for (var step = 0; step < STEP_LIMIT && state(id) != CampaignState.COMPLETED; step++) {
            batch.maintain();
            var advanced = progress.advance();
            mostRunning = Math.max(mostRunning, running(runIds));
            var played = batch.runNextCase(WORKER);
            if (!advanced && !played) {
                break;
            }
        }
        assertThat(state(id)).isEqualTo(CampaignState.COMPLETED);
        assertThat(mostRunning).as("confirmation cases running at once").isLessThanOrEqualTo(1);

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
        assertThat(confirmationSeeds).doesNotContainAnyElementsOf(campaigns.loggedSeeds(id));
        completed.confirmation().runs().forEach(run -> {
            assertThat(seedsOf(run.runId())).containsExactlyInAnyOrderElementsOf(confirmationSeeds);
            assertThat(lanesOf(run.runId())).containsExactly("lane-1");
        });
    }

    private int running(List<UUID> runIds) {
        return runIds.stream()
                .mapToInt(runId -> jdbc.queryForObject(
                        "SELECT count(*) FROM run_case WHERE run_id = ? AND state = 'RUNNING'", Integer.class, runId))
                .sum();
    }

    private List<String> attemptLanes() {
        return jdbc.queryForList("SELECT DISTINCT lane_id FROM case_attempt ORDER BY lane_id", String.class);
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

    /** Runs a search to its short list on the simulator. */
    private UUID searched(CampaignProgress progress) {
        var id = start();
        drive(progress, () -> state(id) == CampaignState.SEARCH_COMPLETED);
        return id;
    }

    private UUID start() {
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
        var seats = node.putArray("seatAssignment");
        for (var seat = 0; seat < 5; seat++) {
            seats.addObject()
                    .put("seatIndex", seat)
                    .put("policyId", policy.id())
                    .put("policyVersion", policy.version());
        }
        var definition = new CampaignDefinition(
                "it confirmation",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(node),
                List.of(3),
                CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                "7",
                "9",
                new SearchAlgorithm(2, 2, 2, 1));
        var campaign = Campaign.queued(UUID.randomUUID(), definition, 4, clock.instant());
        campaigns.create(UUID.randomUUID(), "h".repeat(64), campaign, clock.instant());
        return campaign.campaignId();
    }

    /** Moves the campaign and the execution workers along until {@code done} holds. */
    private void drive(CampaignProgress progress, BooleanSupplier done) {
        for (var step = 0; step < STEP_LIMIT && !done.getAsBoolean(); step++) {
            batch.maintain();
            var advanced = progress.advance();
            if (done.getAsBoolean()) {
                break;
            }
            var played = batch.runNextCase(WORKER);
            if (!advanced && !played) {
                break;
            }
        }
        assertThat(done.getAsBoolean())
                .as("the campaign reached the awaited point")
                .isTrue();
    }

    /**
     * The real evaluation for confirmation, with a small confirmation sample so the test stays quick, and a scripted
     * screening in which only the first candidate survives.
     */
    private record ScriptedSearch(CandidateEvaluation real) implements CandidateEvaluation {

        @Override
        public boolean servesCriterion(int criterionVersion) {
            return real.servesCriterion(criterionVersion);
        }

        @Override
        public int confirmationSeedCount(int criterionVersion) {
            return CONFIRMATION_SEEDS;
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
