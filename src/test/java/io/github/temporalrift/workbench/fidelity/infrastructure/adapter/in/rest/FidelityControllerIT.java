package io.github.temporalrift.workbench.fidelity.infrastructure.adapter.in.rest;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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

import io.github.temporalrift.workbench.TestSecurityConfig;
import io.github.temporalrift.workbench.fidelity.application.FidelityCheckView;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.GetSimulatorFidelityUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityCasesUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.ListFidelityChecksUseCase;
import io.github.temporalrift.workbench.fidelity.application.port.in.StartFidelityCheckUseCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCase;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheck;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckActiveException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCheckNotFoundException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityCounts;
import io.github.temporalrift.workbench.fidelity.domain.FidelityFailure;
import io.github.temporalrift.workbench.fidelity.domain.FidelityIdempotencyConflictException;
import io.github.temporalrift.workbench.fidelity.domain.FidelityMatrix;
import io.github.temporalrift.workbench.fidelity.domain.FidelityVariant;
import io.github.temporalrift.workbench.fidelity.domain.FirstDivergence;
import io.github.temporalrift.workbench.fidelity.domain.InvalidSeedException;
import io.github.temporalrift.workbench.fidelity.domain.SimulatorNotAvailableException;
import io.github.temporalrift.workbench.fidelity.domain.SourceRevisions;
import io.github.temporalrift.workbench.fidelity.domain.VariantLabel;
import io.github.temporalrift.workbench.fidelity.infrastructure.config.FidelityApiJsonConfiguration;
import io.github.temporalrift.workbench.fidelity.support.FidelityFixtures;
import io.github.temporalrift.workbench.shared.Page;
import io.github.temporalrift.workbench.shared.infrastructure.config.SecurityConfig;

@WebMvcTest(
        controllers = FidelityController.class,
        properties = {
            "workbench.ui.oidc-issuer-url=http://localhost:9000/default",
            "workbench.ui.oidc-client-id=simulation-workbench-ui"
        })
@Import({SecurityConfig.class, TestSecurityConfig.class, FidelityApiJsonConfiguration.class})
class FidelityControllerIT {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final UUID CHECK = new UUID(1, 1);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StartFidelityCheckUseCase startCheck;

    @MockitoBean
    private GetFidelityCheckUseCase getCheck;

    @MockitoBean
    private ListFidelityChecksUseCase listChecks;

    @MockitoBean
    private ListFidelityCasesUseCase listCases;

    @MockitoBean
    private GetSimulatorFidelityUseCase getSimulatorFidelity;

    private static FidelityCheckView view() {
        var check = FidelityCheck.queued(
                CHECK,
                "42",
                FidelityFixtures.environment(FidelityFixtures.SIMULATOR_IMAGE, "1111111"),
                FidelityFixtures.environment(FidelityFixtures.LANE_IMAGE, "1111111"),
                "lane-1",
                List.of(
                        new FidelityVariant(VariantLabel.BASELINE, "a".repeat(64), new TreeMap<>()),
                        new FidelityVariant(
                                VariantLabel.LOW,
                                "b".repeat(64),
                                new TreeMap<>(Map.of("game.rules.win-score-threshold", new BigDecimal("16"))))),
                NOW);
        return new FidelityCheckView(check, new FidelityCounts(0, 0, 0, 0, 0));
    }

    private ResultActions start(String body, String... scopes) throws Exception {
        return mockMvc.perform(post("/api/v1/fidelity-checks")
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

    private static final String WRITE = "SCOPE_simulation:write";
    private static final String READ = "SCOPE_simulation:read";

    @Test
    void startingACheckAnswers202WithTheQueuedRecord() throws Exception {
        when(startCheck.handle(any())).thenReturn(view());

        start("{\"seed\":\"42\"}", WRITE)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.fidelityCheckId").value(CHECK.toString()))
                .andExpect(jsonPath("$.seed").value("42"))
                .andExpect(jsonPath("$.state").value("QUEUED"))
                .andExpect(jsonPath("$.verdict").value((Object) null))
                .andExpect(
                        jsonPath("$.lane.services.gameService.sourceRevision").value("1111111"))
                .andExpect(jsonPath("$.simulator.services.gameService.imageDigest")
                        .value(FidelityFixtures.SIMULATOR_IMAGE))
                .andExpect(jsonPath("$.variants[0].label").value("baseline"))
                .andExpect(jsonPath("$.variants[1].overrides['game.rules.win-score-threshold']")
                        .value(16))
                .andExpect(jsonPath("$.counts.total").value(0))
                .andExpect(jsonPath("$.startedAt").value((Object) null));
    }

    @Test
    void aWriteTokenIsNeededToStartACheck() throws Exception {
        start("{\"seed\":\"42\"}", READ)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }

    @Test
    void aMalformedSeedIsAnInvalidRequestNamingTheField() throws Exception {
        start("{\"seed\":\"-1\"}", WRITE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("seed"));
    }

    @Test
    void aSeedThatOverflowsSixtyFourBitsIsAnInvalidRequest() throws Exception {
        when(startCheck.handle(any())).thenThrow(new InvalidSeedException("18446744073709551616"));

        start("{\"seed\":\"18446744073709551616\"}", WRITE)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("seed"));
    }

    @Test
    void anActiveCheckIsAConflict() throws Exception {
        when(startCheck.handle(any())).thenThrow(new FidelityCheckActiveException());

        start("{\"seed\":\"42\"}", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIDELITY_CHECK_ACTIVE"));
    }

    @Test
    void anUnavailableSimulatorIsAConflict() throws Exception {
        when(startCheck.handle(any())).thenThrow(new SimulatorNotAvailableException("The simulator is not enabled"));

        start("{\"seed\":\"42\"}", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIMULATOR_NOT_AVAILABLE"));
    }

    @Test
    void aReusedKeyForAnotherSeedIsAConflict() throws Exception {
        when(startCheck.handle(any())).thenThrow(new FidelityIdempotencyConflictException());

        start("{\"seed\":\"43\"}", WRITE)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void aCheckIsReadByItsId() throws Exception {
        when(getCheck.handle(CHECK)).thenReturn(view());

        read("/api/v1/fidelity-checks/" + CHECK, READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fidelityCheckId").value(CHECK.toString()))
                .andExpect(jsonPath("$.failure").value((Object) null));
    }

    @Test
    void anUnknownCheckIsNotFound() throws Exception {
        when(getCheck.handle(any())).thenThrow(new FidelityCheckNotFoundException(CHECK));

        read("/api/v1/fidelity-checks/" + CHECK, READ)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void checksArePagedWithTheirTotal() throws Exception {
        when(listChecks.handle(any())).thenReturn(new Page<>(List.of(view()), 3));

        read("/api/v1/fidelity-checks?state=QUEUED&limit=1", READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].state").value("QUEUED"));
    }

    @Test
    void anOutOfRangeLimitIsAnInvalidRequest() throws Exception {
        read("/api/v1/fidelity-checks?limit=0", READ)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aDivergedCaseCarriesItsFirstDivergenceAndNoFailure() throws Exception {
        var diverged = FidelityCase.pending(
                        7,
                        UUID.randomUUID(),
                        VariantLabel.HIGH,
                        3,
                        FidelityMatrix.factionSets().getFirst(),
                        2,
                        "random",
                        UUID.randomUUID())
                .diverged(new FirstDivergence(12, "DECISION", Map.of("seatIndex", 1), Map.of("seatIndex", 2)));
        when(listCases.handle(any())).thenReturn(new Page<>(List.of(diverged), 1));

        read("/api/v1/fidelity-checks/" + CHECK + "/cases?result=DIVERGED&variantLabel=high", READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].variantLabel").value("high"))
                .andExpect(jsonPath("$.items[0].playerCount").value(3))
                .andExpect(jsonPath("$.items[0].seatRotation").value(2))
                .andExpect(jsonPath("$.items[0].result").value("DIVERGED"))
                .andExpect(jsonPath("$.items[0].firstDivergence.kind").value("DECISION"))
                .andExpect(jsonPath("$.items[0].firstDivergence.step").value(12))
                .andExpect(jsonPath("$.items[0].failure").value((Object) null));
    }

    @Test
    void anUnreproducibleCaseCarriesItsFailureAndNoDivergence() throws Exception {
        var unreproducible = FidelityCase.pending(
                        0,
                        UUID.randomUUID(),
                        VariantLabel.BASELINE,
                        4,
                        FidelityMatrix.factionSets().get(10),
                        0,
                        "faction-greedy",
                        UUID.randomUUID())
                .unreproducible(new FidelityFailure("RUNNER_TIMEOUT", "timed out"));
        when(listCases.handle(any())).thenReturn(new Page<>(List.of(unreproducible), 1));

        read("/api/v1/fidelity-checks/" + CHECK + "/cases", READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].result").value("UNREPRODUCIBLE"))
                .andExpect(jsonPath("$.items[0].failure.code").value("RUNNER_TIMEOUT"))
                .andExpect(jsonPath("$.items[0].firstDivergence").value((Object) null));
    }

    @Test
    void theSimulatorsRevisionsAndTheGateAreRead() throws Exception {
        when(getSimulatorFidelity.handle())
                .thenReturn(new GetSimulatorFidelityUseCase.SimulatorFidelity(
                        new SourceRevisions("1111111", "2222222", "3333333"), false, null));

        read("/api/v1/simulator/fidelity", READ)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceRevisions.gameService").value("1111111"))
                .andExpect(jsonPath("$.sourceRevisions.timelineService").value("2222222"))
                .andExpect(jsonPath("$.sourceRevisions.readService").value("3333333"))
                .andExpect(jsonPath("$.established").value(false))
                .andExpect(jsonPath("$.record").value((Object) null));
    }

    @Test
    void theGateIsAConflictWhenTheSimulatorIsNotEnabled() throws Exception {
        when(getSimulatorFidelity.handle())
                .thenThrow(new SimulatorNotAvailableException("The simulator is not enabled"));

        read("/api/v1/simulator/fidelity", READ)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SIMULATOR_NOT_AVAILABLE"));
    }

    @Test
    void readingNeedsTheReadScope() throws Exception {
        read("/api/v1/fidelity-checks", WRITE)
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("INSUFFICIENT_SCOPE"));
    }
}
