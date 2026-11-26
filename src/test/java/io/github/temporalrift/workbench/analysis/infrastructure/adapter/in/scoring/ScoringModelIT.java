package io.github.temporalrift.workbench.analysis.infrastructure.adapter.in.scoring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.WorkbenchIntegrationTest;

/** A designer evaluates a scoring design from per-era bet rates and reads win ratios and sensitivity back. */
@WorkbenchIntegrationTest
class ScoringModelIT {

    private static final String PATH = "/api/v1/scoring-models/evaluations";
    private static final String[] FACTIONS = {"ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS"};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    private ObjectNode request() {
        var request = objectMapper.createObjectNode();
        var engine = request.putObject("engine");
        engine.put("winThreshold", 12);
        engine.put("maxEras", 5);
        var rates = request.putArray("rates");
        var factions = engine.putArray("factions");
        for (var faction : FACTIONS) {
            var item = factions.addObject();
            item.put("faction", faction);
            item.putArray("winPoints").add(4);
            item.put("missPoints", 0);
            item.putArray("resetOn");
            for (var count : new int[] {3, 4, 5}) {
                var rate = rates.addObject();
                rate.put("playerCount", count);
                rate.put("faction", faction);
                rate.put("winRate", 0.5);
                rate.put("missRate", 0.5);
                rate.put("extraPointsPerEra", 0);
            }
        }
        request.putArray("playerCounts").add(3).add(4).add(5);
        request.put("gamesPerPlayerCount", 1000);
        request.put("seed", 11);
        return request;
    }

    private ResultActions send(ObjectNode request, String scope) throws Exception {
        return mockMvc.perform(post(PATH)
                .with(jwt().authorities(new SimpleGrantedAuthority(scope)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private ResultActions send(ObjectNode request) throws Exception {
        return send(request, "SCOPE_simulation:read");
    }

    @Test
    void ratiosEndingsAndSensitivityComeBack() throws Exception {
        var request = request();
        request.putArray("sweep").addObject().put("parameter", "winThreshold").put("step", 2);

        send(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(15))
                .andExpect(jsonPath("$.endings.length()").value(3))
                .andExpect(jsonPath("$.sensitivity.length()").value(30));
    }

    @Test
    void equalRequestsGiveByteIdenticalResponses() throws Exception {
        var request = request();
        request.putArray("sweep")
                .addObject()
                .put("parameter", "factions.WEAVERS.winPoints[0]")
                .put("step", 1);

        var first = send(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var second = send(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(second).isEqualTo(first);
    }

    @Test
    void ratesAboveOneAreRefusedNamingTheItem() throws Exception {
        var request = request();
        var item = (ObjectNode) request.get("rates").get(3);
        item.put("winRate", 0.7);
        item.put("missRate", 0.4);

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("rates[3]"));
    }

    @Test
    void aMissingRatesItemIsRefused() throws Exception {
        var request = request();
        var rates = request.withArray("rates");
        for (var index = 0; index < rates.size(); index++) {
            var item = rates.get(index);
            if (item.get("playerCount").asInt() == 4
                    && "PROPHETS".equals(item.get("faction").asString())) {
                rates.remove(index);
                break;
            }
        }

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("rates"));
    }

    @Test
    void anEmptyWinPointsListIsRefusedNamingIt() throws Exception {
        var request = request();
        ((ObjectNode) request.get("engine").get("factions").get(1)).putArray("winPoints");

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("engine.factions[1].winPoints"));
    }

    @Test
    void anObjectiveWinsWithoutABonusIsRefused() throws Exception {
        var request = request();
        ((ObjectNode) request.get("engine").get("factions").get(0)).put("objectiveWins", 3);

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("engine.factions[0].objectiveBonus"));
    }

    @Test
    void anUnparsableSweepPathIsRefusedNamingIt() throws Exception {
        for (var path : new String[] {"factions.WEAVERS.winPoints[2147483648]", "rates.3.NOBODY.winRate"}) {
            var request = request();
            request.putArray("sweep").addObject().put("parameter", path).put("step", 1);

            send(request)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.field").value("sweep[0].parameter"));
        }
    }

    @Test
    void aCallWithoutTheReadScopeIsForbidden() throws Exception {
        send(request(), "SCOPE_simulation:other").andExpect(status().isForbidden());
    }

    private ResultActions rescore(String runId, ObjectNode body, String scope) throws Exception {
        return mockMvc.perform(post("/api/v1/runs/" + runId + "/rescorings")
                .with(jwt().authorities(new SimpleGrantedAuthority(scope)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ObjectNode rescoring() {
        var body = objectMapper.createObjectNode();
        body.set("engine", request().get("engine"));
        body.put("variantLabel", "baseline");
        body.put("tableId", "table-1");
        return body;
    }

    @Test
    void rescoringAnUnknownRunIsNotFound() throws Exception {
        rescore("00000000-0000-0000-0000-0000000000aa", rescoring(), "SCOPE_simulation:read")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rescoringWithoutTheReadScopeIsForbidden() throws Exception {
        rescore("00000000-0000-0000-0000-0000000000aa", rescoring(), "SCOPE_simulation:other")
                .andExpect(status().isForbidden());
    }

    @Test
    void rescoringWithAnEmptyWinPointsListIsRefusedNamingIt() throws Exception {
        var body = rescoring();
        ((ObjectNode) body.get("engine").get("factions").get(1)).putArray("winPoints");

        rescore("00000000-0000-0000-0000-0000000000aa", body, "SCOPE_simulation:read")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("engine.factions[1].winPoints"));
    }

    @Test
    void rescoringWithAnOverriddenPointsOutsideRangeIsRefusedNamingIt() throws Exception {
        var body = rescoring();
        body.putArray("otherReasonPoints")
                .addObject()
                .put("scoreReason", "CHAIN_COMPLETED")
                .put("points", 51);

        rescore("00000000-0000-0000-0000-0000000000aa", body, "SCOPE_simulation:read")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("otherReasonPoints[0].points"));
    }
}
