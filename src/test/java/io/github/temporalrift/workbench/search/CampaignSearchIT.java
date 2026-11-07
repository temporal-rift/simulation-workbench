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
import io.github.temporalrift.workbench.execution.LaneEnvironments;
import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;
import io.github.temporalrift.workbench.policy.PolicyCatalog;
import io.github.temporalrift.workbench.search.application.command.CampaignProgress;
import io.github.temporalrift.workbench.search.application.command.CampaignSettings;
import io.github.temporalrift.workbench.search.application.port.in.PauseCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ResumeCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.SetCampaignParallelismUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyRef;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignExperiments;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRuns;
import io.github.temporalrift.workbench.search.domain.port.out.CandidateEvaluation;
import io.github.temporalrift.workbench.shared.execution.RunPhase;

/**
 * A whole search on the scripted lanes: what is exercised is the campaign itself, the experiments it freezes, the runs
 * that play its waves, the records and the log. The balance criterion is scripted so that the search ends after a few
 * rounds, because every game is a full case of the matrix.
 */
@WorkbenchIntegrationTest
class CampaignSearchIT {

    private static final String WORKER = "campaign-it";
    private static final int STEP_LIMIT = 100_000;
    private static final BundleDigests BASELINE =
            new BundleDigests(VariantRulesFixtures.baseline().digest(), "b".repeat(64));

    @Autowired
    private CampaignRepository campaigns;

    @Autowired
    private CampaignExperiments experiments;

    @Autowired
    private CampaignRuns runs;

    @Autowired
    private PauseCampaignUseCase pause;

    @Autowired
    private ResumeCampaignUseCase resume;

    @Autowired
    private SetCampaignParallelismUseCase parallelism;

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
    void cleanAndServeTheBaseline() {
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
        lanes.serve(BASELINE, BASELINE);
    }

    @Test
    void aSearchIsTheSameWhateverTheParallelismAndHoweverItIsInterrupted() {
        var straight = start(1);
        var straightProgress = progress();
        drive(straightProgress, () -> over(straight));
        assertThat(campaigns.find(straight).orElseThrow().campaign().state()).isEqualTo(CampaignState.SEARCH_COMPLETED);

        var interrupted = start(4);
        var firstProcess = progress();
        drive(firstProcess, () -> roundOnePlanned(interrupted));
        assertThat(roundOnePlanned(interrupted)).isTrue();
        pause.handle(interrupted);
        var playedWhilePaused = batch.runNextCase(WORKER);
        assertThat(playedWhilePaused).as("a paused campaign starts no game").isFalse();

        var secondProcess = progress();
        secondProcess.recover();
        resume.handle(interrupted);
        parallelism.handle(interrupted, 4);
        drive(secondProcess, () -> over(interrupted));

        var finished = campaigns.find(interrupted).orElseThrow().campaign();
        assertThat(finished.state()).isEqualTo(CampaignState.SEARCH_COMPLETED);
        assertThat(finished.shortList())
                .isEqualTo(campaigns.find(straight).orElseThrow().campaign().shortList());
        assertThat(canonicalLog(interrupted)).isNotEmpty().isEqualTo(canonicalLog(straight));
        assertThat(campaigns.games(interrupted)).isEqualTo(campaigns.games(straight));
        assertThat(campaigns.waves(interrupted)).hasSameSizeAs(campaigns.waves(straight));
        campaigns
                .waves(interrupted)
                .forEach(wave -> assertThat(
                                runs.progress(wave.runId()).orElseThrow().phase())
                        .isEqualTo(RunPhase.COMPLETED));
        assertThat(finished.spentSeconds()).isGreaterThanOrEqualTo(0);
    }

    private CampaignProgress progress() {
        return new CampaignProgress(
                campaigns, experiments, runs, scriptedCriterion(), () -> true, new CampaignSettings(50_000), clock);
    }

    /** One table on which the first catalogued policy plays every seat. */
    private io.github.temporalrift.workbench.search.domain.campaign.SeatTable uniformTable() {
        var policy = policies.list().getFirst();
        var major = policy.version().substring(0, policy.version().indexOf('.'));
        return new io.github.temporalrift.workbench.search.domain.campaign.SeatTable(
                "it-table",
                java.util.Collections.nCopies(
                        5, new io.github.temporalrift.workbench.search.domain.campaign.PolicyRef(policy.id(), major)));
    }

    /** Only the first candidate survives: the others fail the gate, so the short list is the same every round. */
    private CandidateEvaluation scriptedCriterion() {
        return new CandidateEvaluation() {
            @Override
            public boolean servesCriterion(int criterionVersion) {
                return true;
            }

            @Override
            public int confirmationSeedCount(int criterionVersion) {
                return 100;
            }

            @Override
            public List<io.github.temporalrift.workbench.search.domain.campaign.SeatTable> screeningTables(
                    int criterionVersion) {
                return List.of(uniformTable());
            }

            @Override
            public List<PolicyRef> confirmationPolicies(int criterionVersion) {
                return List.of();
            }

            @Override
            public Evaluation confirm(int criterionVersion, UUID runId, String variantLabel) {
                throw new UnsupportedOperationException("this search is not confirmed");
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
        };
    }

    private UUID start(int games) {
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
        var definition = new CampaignDefinition(
                "it search",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(node),
                List.of(3),
                CampaignDefinition.DEFAULT_BUDGET_SECONDS,
                "7",
                "9",
                new SearchAlgorithm(2, 2, 2, 1));
        var campaign = Campaign.queued(UUID.randomUUID(), definition, games, clock.instant());
        campaigns.create(UUID.randomUUID(), "h".repeat(64), campaign, clock.instant());
        return campaign.campaignId();
    }

    private boolean over(UUID id) {
        var state = campaigns.find(id).orElseThrow().campaign().state();
        return state.isTerminal() || state == CampaignState.SEARCH_COMPLETED;
    }

    private boolean roundOnePlanned(UUID id) {
        return campaigns.waves(id).stream().anyMatch(wave -> wave.round() == 1);
    }

    private List<String> canonicalLog(UUID id) {
        return campaigns.log(id, 10_000, 0).items().stream()
                .map(LogEntry::canonicalJson)
                .toList();
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
}
