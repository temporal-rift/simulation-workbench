package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.search.application.port.in.CancelCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.CreateCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignLogUseCase;
import io.github.temporalrift.workbench.search.application.port.in.GetCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ListCampaignsUseCase;
import io.github.temporalrift.workbench.search.application.port.in.PauseCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.ResumeCampaignUseCase;
import io.github.temporalrift.workbench.search.application.port.in.SetCampaignParallelismUseCase;
import io.github.temporalrift.workbench.search.application.port.in.StartConfirmationUseCase;
import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignFailure;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignIdempotencyConflictException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignNotFoundException;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignState;
import io.github.temporalrift.workbench.search.domain.campaign.Confirmation;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignException;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignReason;
import io.github.temporalrift.workbench.search.domain.campaign.InvalidCampaignStateException;
import io.github.temporalrift.workbench.search.domain.campaign.PolicyNotAvailableException;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.campaign.SeedOverlapException;
import io.github.temporalrift.workbench.search.domain.campaign.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.infrastructure.config.SearchApiJsonConfiguration;
import io.github.temporalrift.workbench.shared.Page;
import io.github.temporalrift.workbench.shared.infrastructure.config.SecurityConfig;

@WebMvcTest(
        controllers = CampaignController.class,
        properties = {
            "workbench.ui.oidc-issuer-url=http://localhost:9000/default",
            "workbench.ui.oidc-client-id=simulation-workbench-ui"
        })
@Import({SecurityConfig.class, TestSecurityConfig.class, SearchApiJsonConfiguration.class, CampaignControls.class})
class CampaignControllerIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID CAMPAIGN = new UUID(1, 1);
    private static final String WRITE = "SCOPE_simulation:write";
    private static final String READ = "SCOPE_simulation:read";

    private static final String BODY = """
            {
              "name": "threshold-halving-01",
              "baseline": {
                "rulesArtifact": {"digest": "%s"},
                "contentArtifact": {"digest": "%s"}
              },
              "parameterSpaceVersion": 1,
              "criterionVersion": 1,
              "policies": [{
                "id": "faction-greedy",
                "version": "1.0.0",
                "artifactDigest": "%s",
                "parameters": {}
              }],
              "playerCounts": [3, 4],
              "searchMasterSeed": "7",
              "confirmationMasterSeed": "9",
              "algorithm": {
                "version": "SUCCESSIVE_HALVING_V2",
                "initialCandidates": 8,
                "screeningSeeds": 4,
                "eta": 2,
                "shortListSize": 2
              }
            }
            """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64));

    private static final String MINIMAL = """
            {
              "name": "threshold-halving-01",
              "baseline": {
                "rulesArtifact": {"digest": "%s"},
                "contentArtifact": {"digest": "%s"}
              },
              "policies": [{
                "id": "faction-greedy",
                "version": "1.0.0",
                "artifactDigest": "%s",
                "parameters": {}
              }],
              "playerCounts": [3]
            }
            """.formatted("a".repeat(64), "b".repeat(64), "c".repeat(64));

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateCampaignUseCase create;

    @MockitoBean
    private GetCampaignUseCase get;

    @MockitoBean
    private ListCampaignsUseCase list;

    @MockitoBean
    private GetCampaignLogUseCase log;

    @MockitoBean
    private PauseCampaignUseCase pause;

    @MockitoBean
    private ResumeCampaignUseCase resume;

    @MockitoBean
    private CancelCampaignUseCase cancel;

    @MockitoBean
    private SetCampaignParallelismUseCase parallelism;

    @MockitoBean
    private StartConfirmationUseCase startConfirmation;

    @Test
    void aCommandCarriesItsKey() throws Exception {
        var key = UUID.randomUUID();
        when(parallelism.handle(CAMPAIGN, 8, key)).thenReturn(campaign());

        mockMvc.perform(put("/api/v1/campaigns/" + CAMPAIGN + "/parallelism")
                        .with(jwt().authorities(authorities(WRITE)))
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parallelism\":8}"))
                .andExpect(status().isOk());
    }

    @Test
    void aKeyAnotherRequestUsedAnswers409() throws Exception {
        when(startConfirmation.handle(eq(CAMPAIGN), any()))
                .thenThrow(new CampaignIdempotencyConflictException(UUID.randomUUID()));

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    private static Campaign campaign() {
        var mapper = JsonMapper.builder().build();
        var baseline = mapper.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "a".repeat(64));
        baseline.putObject("contentArtifact").put("digest", "b".repeat(64));
        var policy = mapper.createObjectNode()
                .put("id", "faction-greedy")
                .put("version", "1.0.0")
                .put("artifactDigest", "c".repeat(64));
        policy.putObject("parameters");
        var definition = new CampaignDefinition(
                "threshold-halving-01",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(policy),
                List.of(3, 4),
                null,
                "7",
                "9",
                new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2, 8, 4, 2, 2));
        return Campaign.queued(CAMPAIGN, definition, 4, NOW);
    }

    private ResultActions send(String path, String body, String... scopes) throws Exception {
        return mockMvc.perform(post(path)
                .with(jwt().authorities(authorities(scopes)))
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions read(String path, String... scopes) throws Exception {
        return mockMvc.perform(get(path).with(jwt().authorities(authorities(scopes))));
    }

    private static SimpleGrantedAuthority[] authorities(String... scopes) {
        return Arrays.stream(scopes).map(SimpleGrantedAuthority::new).toArray(SimpleGrantedAuthority[]::new);
    }

    @Test
    void creatingACampaignAnswers201WithTheQueuedCampaignAndEveryRequiredField() throws Exception {
        when(create.handle(any())).thenReturn(campaign());

        send("/api/v1/campaigns", BODY, WRITE)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaignId").value(CAMPAIGN.toString()))
                .andExpect(jsonPath("$.name").value("threshold-halving-01"))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.budgetSeconds").doesNotExist())
                .andExpect(jsonPath("$.parallelism").value(4))
                .andExpect(jsonPath("$.playerCounts[0]").value(3))
                .andExpect(jsonPath("$.baseline.rulesArtifact.digest").value("a".repeat(64)))
                .andExpect(jsonPath("$.policies[0].id").value("faction-greedy"))
                .andExpect(jsonPath("$.algorithm.version").value("SUCCESSIVE_HALVING_V2"))
                .andExpect(jsonPath("$.spentSeconds").value(0))
                .andExpect(jsonPath("$.round").value(0))
                .andExpect(jsonPath("$.shortList").isEmpty())
                .andExpect(jsonPath("$.confirmation").value((Object) null))
                .andExpect(jsonPath("$.failure").value((Object) null))
                .andExpect(jsonPath("$.startedAt").value((Object) null))
                .andExpect(jsonPath("$.finishedAt").value((Object) null));
    }

    @Test
    void theCommandCarriesTheKeyTheDefinitionAndTheDefaultsOfTheContract() throws Exception {
        when(create.handle(any())).thenReturn(campaign());
        var key = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/campaigns")
                        .with(jwt().authorities(authorities(WRITE)))
                        .header("Idempotency-Key", key.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(BODY))
                .andExpect(status().isCreated());

        verify(create)
                .handle(argThat(command -> command.idempotencyKey().equals(key)
                        && command.parallelism() == 4
                        && command.playerCounts().equals(List.of(3, 4))
                        && command.searchMasterSeed().equals("7")
                        && command.algorithm()
                                .equals(new CreateCampaignUseCase.AlgorithmRequest(
                                        "SUCCESSIVE_HALVING_V2", 8, 4, 2, 2))));
    }

    @Test
    void aMinimalDefinitionLeavesTheResolvingToTheService() throws Exception {
        when(create.handle(any())).thenReturn(campaign());

        send("/api/v1/campaigns", MINIMAL, WRITE).andExpect(status().isCreated());

        verify(create)
                .handle(argThat(command -> command.parameterSpaceVersion() == null
                        && command.criterionVersion() == null
                        && command.searchMasterSeed() == null
                        && command.confirmationMasterSeed() == null
                        && command.algorithm() == null
                        && command.parallelism() == 4));
    }

    @Test
    void aDefinitionWithoutPoliciesLeavesThemToTheService() throws Exception {
        when(create.handle(any())).thenReturn(campaign());
        var withoutPolicies = """
                {
                  "name": "threshold-halving-01",
                  "baseline": {
                    "rulesArtifact": {"digest": "%s"},
                    "contentArtifact": {"digest": "%s"}
                  },
                  "playerCounts": [3]
                }
                """.formatted("a".repeat(64), "b".repeat(64));

        send("/api/v1/campaigns", withoutPolicies, WRITE).andExpect(status().isCreated());

        verify(create).handle(argThat(command -> command.policies() == null));
    }

    @Test
    void anEmptyPolicyListAnswers400AsAnInvalidValue() throws Exception {
        var empty = MINIMAL.replaceFirst("(?s)\"policies\": \\[.*?\\],", "\"policies\": [],");

        send("/api/v1/campaigns", empty, WRITE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAMPAIGN"))
                .andExpect(jsonPath("$.field").value("policies"))
                .andExpect(jsonPath("$.reason").value("INVALID_VALUE"));
        verify(create, never()).handle(any());
    }

    @Test
    void aPartialAlgorithmPassesOnlyTheFieldsItSets() throws Exception {
        when(create.handle(any())).thenReturn(campaign());
        var partial = MINIMAL.replace(
                "\"playerCounts\": [3]", "\"playerCounts\": [3], \"algorithm\": {\"initialCandidates\": 10}");

        send("/api/v1/campaigns", partial, WRITE).andExpect(status().isCreated());

        verify(create)
                .handle(argThat(command -> command.algorithm()
                        .equals(new CreateCampaignUseCase.AlgorithmRequest(null, 10, null, null, null))));
    }

    @Test
    void creatingNeedsTheWriteScope() throws Exception {
        send("/api/v1/campaigns", BODY, READ).andExpect(status().isForbidden());
    }

    @Test
    void aDefinitionTheServiceRefusesAnswers400WithTheFieldAndReason() throws Exception {
        when(create.handle(any()))
                .thenThrow(new InvalidCampaignException(
                        InvalidCampaignReason.EQUAL_MASTER_SEEDS,
                        "confirmationMasterSeed",
                        "The search and confirmation master seeds must differ"));

        send("/api/v1/campaigns", BODY, WRITE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAMPAIGN"))
                .andExpect(jsonPath("$.field").value("confirmationMasterSeed"))
                .andExpect(jsonPath("$.reason").value("EQUAL_MASTER_SEEDS"));
    }

    @Test
    void aBodyThatBreaksTheSchemaAnswers400AsAnInvalidValue() throws Exception {
        send("/api/v1/campaigns", BODY.replace("\"criterionVersion\": 1", "\"criterionVersion\": 0"), WRITE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAMPAIGN"))
                .andExpect(jsonPath("$.field").value("criterionVersion"))
                .andExpect(jsonPath("$.reason").value("INVALID_VALUE"));
    }

    @Test
    void aKeyReusedWithAnotherDefinitionAnswers409() throws Exception {
        when(create.handle(any())).thenThrow(new CampaignIdempotencyConflictException(UUID.randomUUID()));

        send("/api/v1/campaigns", BODY, WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aFailedCampaignShowsWhyItStopped() throws Exception {
        var failed = campaign()
                .start(NOW)
                .fail(new CampaignFailure(CampaignFailure.CANDIDATE_GENERATION_EXHAUSTED, "too few candidates"), NOW);
        when(get.handle(CAMPAIGN)).thenReturn(failed);

        read("/api/v1/campaigns/" + CAMPAIGN, READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("FAILED"))
                .andExpect(jsonPath("$.failure.code").value("CANDIDATE_GENERATION_EXHAUSTED"));
    }

    @Test
    void anUnknownCampaignAnswers404() throws Exception {
        when(get.handle(CAMPAIGN)).thenThrow(new CampaignNotFoundException(CAMPAIGN));

        read("/api/v1/campaigns/" + CAMPAIGN, READ)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void campaignsAreListedWithTheirTotal() throws Exception {
        when(list.handle(new ListCampaignsUseCase.Query(50, 0))).thenReturn(new Page<>(List.of(campaign()), 1));

        read("/api/v1/campaigns", READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].campaignId").value(CAMPAIGN.toString()));
    }

    @Test
    void theLogShowsEveryDecisionWithItsEvidenceAndRedraws() throws Exception {
        var overrides = new TreeMap<String, BigDecimal>();
        overrides.put("game.rules.win-score-threshold", new BigDecimal("22"));
        var generated = new LogEntry(
                1,
                0,
                "c-0001",
                overrides,
                "d".repeat(64),
                List.of(),
                0,
                3,
                null,
                LogEntry.Decision.GENERATED,
                null,
                null);
        var discarded = new LogEntry(
                2,
                0,
                "c-0001",
                overrides,
                "d".repeat(64),
                List.of("11", "12"),
                2,
                3,
                new LogEntry.Evaluation("REJECTED", null, 1.5),
                LogEntry.Decision.DISCARDED,
                LogEntry.Reason.GATE_FAILS,
                LogEntry.Evidence.failingLines(List.of("G-01")));
        var notMet = new LogEntry(
                3,
                4,
                "c-0002",
                overrides,
                "e".repeat(64),
                List.of("11", "12"),
                2,
                0,
                new LogEntry.Evaluation("REJECTED", 27.5, 0.4),
                LogEntry.Decision.DISCARDED,
                LogEntry.Reason.GATES_NOT_MET,
                LogEntry.Evidence.failingLines(List.of("G-05")));
        var version1 = new LogEntry(
                4,
                0,
                "c-0003",
                overrides,
                "f".repeat(64),
                List.of("11", "12"),
                2,
                0,
                new LogEntry.Evaluation("UNCONFIRMED", 3.0),
                LogEntry.Decision.KEPT,
                null,
                null);
        when(log.handle(new GetCampaignLogUseCase.Query(CAMPAIGN, 50, 0)))
                .thenReturn(new Page<>(List.of(generated, discarded, notMet, version1), 4));

        read("/api/v1/campaigns/" + CAMPAIGN + "/log", READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(4))
                .andExpect(jsonPath("$.items[1].evaluation.gateMiss").value(1.5))
                .andExpect(jsonPath("$.items[2].reason").value("GATES_NOT_MET"))
                .andExpect(jsonPath("$.items[2].evidence.lineIds[0]").value("G-05"))
                .andExpect(jsonPath("$.items[2].evaluation.gateMiss").value(0.4))
                // The field is required by the contract, so a missing gate miss is written as null, not left out.
                .andExpect(jsonPath("$.items[3].evaluation").value(org.hamcrest.Matchers.hasKey("gateMiss")))
                .andExpect(jsonPath("$.items[3].evaluation.gateMiss").value((Object) null))
                .andExpect(jsonPath("$.items[0].decision").value("GENERATED"))
                .andExpect(jsonPath("$.items[0].redraws").value(3))
                .andExpect(jsonPath("$.items[0].evaluation").value((Object) null))
                .andExpect(jsonPath("$.items[0].reason").value((Object) null))
                .andExpect(jsonPath("$.items[0].evidence").value((Object) null))
                .andExpect(jsonPath("$.items[1].reason").value("GATE_FAILS"))
                .andExpect(jsonPath("$.items[1].evidence.lineIds[0]").value("G-01"))
                .andExpect(jsonPath("$.items[1].evaluation.verdict").value("REJECTED"))
                .andExpect(jsonPath("$.items[1].evaluation.score").value((Object) null))
                .andExpect(jsonPath("$.items[1].seeds[1]").value("12"))
                .andExpect(jsonPath("$.items[1].overrides['game.rules.win-score-threshold']")
                        .value(22));
    }

    @Test
    void pausingAnswers200AndAStateThatDoesNotAllowItAnswers409() throws Exception {
        when(pause.handle(eq(CAMPAIGN), any())).thenReturn(campaign().pause());

        send("/api/v1/campaigns/" + CAMPAIGN + "/pause", "", WRITE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PAUSED"));

        when(resume.handle(eq(CAMPAIGN), any()))
                .thenThrow(
                        new InvalidCampaignStateException("resume", campaign().state()));
        send("/api/v1/campaigns/" + CAMPAIGN + "/resume", "", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CAMPAIGN_STATE"));
    }

    @Test
    void cancellingAnswers200() throws Exception {
        when(cancel.handle(eq(CAMPAIGN), any())).thenReturn(campaign().cancel(NOW));

        send("/api/v1/campaigns/" + CAMPAIGN + "/cancel", "", WRITE)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANCELLED"))
                .andExpect(jsonPath("$.finishedAt").value("2026-01-01T00:00:00Z"));
    }

    @Test
    void parallelismIsChangedWithAPutAndRefusedBelowOne() throws Exception {
        when(parallelism.handle(eq(CAMPAIGN), eq(2), any()))
                .thenReturn(campaign().withParallelism(2));

        mockMvc.perform(put("/api/v1/campaigns/" + CAMPAIGN + "/parallelism")
                        .with(jwt().authorities(authorities(WRITE)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parallelism\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.parallelism").value(2));

        mockMvc.perform(put("/api/v1/campaigns/" + CAMPAIGN + "/parallelism")
                        .with(jwt().authorities(authorities(WRITE)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parallelism\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CAMPAIGN"))
                .andExpect(jsonPath("$.field").value("parallelism"));
    }

    @Test
    void confirmingAnswers202WithTheConfirmationAndNoSeedOverlap() throws Exception {
        var run = new Confirmation.Run("c-0000", new UUID(2, 2), new UUID(3, 3), null);
        var baseline = new Confirmation.Run(
                "baseline", new UUID(2, 1), new UUID(3, 1), new Confirmation.Evaluation("CONFIRMED", 2.5));
        var confirming = campaign()
                .start(NOW)
                .completeSearch(List.of("c-0000"))
                .startConfirmation(new Confirmation(100, List.of(baseline, run)));
        when(startConfirmation.handle(eq(CAMPAIGN), any())).thenReturn(confirming);

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.state").value("CONFIRMING"))
                .andExpect(jsonPath("$.confirmation.seedCount").value(100))
                .andExpect(jsonPath("$.confirmation.seedOverlap").value(false))
                .andExpect(jsonPath("$.confirmation.runs.length()").value(2))
                .andExpect(jsonPath("$.confirmation.runs[0].candidateId").value("baseline"))
                .andExpect(jsonPath("$.confirmation.runs[0].evaluation.verdict").value("CONFIRMED"))
                .andExpect(jsonPath("$.confirmation.runs[0].evaluation.score").value(2.5))
                .andExpect(jsonPath("$.confirmation.runs[1].candidateId").value("c-0000"))
                .andExpect(jsonPath("$.confirmation.runs[1].evaluation").value((Object) null));
    }

    @Test
    void confirmingFromAnotherStateAnswers409() throws Exception {
        when(startConfirmation.handle(eq(CAMPAIGN), any()))
                .thenThrow(new InvalidCampaignStateException("startConfirmation", CampaignState.RUNNING));

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_CAMPAIGN_STATE"));
    }

    @Test
    void confirmingOnSeedsTheSearchPlayedAnswers409SeedOverlap() throws Exception {
        when(startConfirmation.handle(eq(CAMPAIGN), any())).thenThrow(new SeedOverlapException(List.of("42")));

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SEED_OVERLAP"));
    }

    @Test
    void confirmingWithTheSimulatorDisabledAnswers409() throws Exception {
        when(startConfirmation.handle(eq(CAMPAIGN), any()))
                .thenThrow(new SimulatorNotAvailableException("not enabled"));

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIMULATOR_NOT_AVAILABLE"));
    }

    @Test
    void confirmingWithAPolicyNoBundleServesAnswers409() throws Exception {
        when(startConfirmation.handle(eq(CAMPAIGN), any())).thenThrow(new PolicyNotAvailableException("no bundle"));

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("POLICY_NOT_AVAILABLE"));
    }

    @Test
    void confirmingAnUnknownCampaignAnswers404() throws Exception {
        when(startConfirmation.handle(eq(CAMPAIGN), any())).thenThrow(new CampaignNotFoundException(CAMPAIGN));

        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", WRITE).andExpect(status().isNotFound());
    }

    @Test
    void confirmingNeedsTheWriteScope() throws Exception {
        send("/api/v1/campaigns/" + CAMPAIGN + "/confirmation", "", READ).andExpect(status().isForbidden());
    }

    @Test
    void mutatingNeedsTheWriteScope() throws Exception {
        send("/api/v1/campaigns/" + CAMPAIGN + "/pause", "", READ).andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/campaigns/" + CAMPAIGN + "/parallelism")
                        .with(jwt().authorities(authorities(READ)))
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parallelism\":2}"))
                .andExpect(status().isForbidden());
    }
}
