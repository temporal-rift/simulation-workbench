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

/** A designer evaluates a scoring design from per-era rates of score reasons and reads win ratios and sensitivity. */
@WorkbenchIntegrationTest
class ScoringModelIT {

    private static final String PATH = "/api/v1/scoring-models/evaluations";
    private static final String[] FACTIONS = {"ERASERS", "PROPHETS", "REVISIONISTS", "WEAVERS", "ACTIVISTS"};
    private static final String[] WIN_REASONS = {
        "ANNIHILATED_OUTCOME", "FULFILLMENT_SUCCEEDED", "SECRET_OUTCOME_WON", "CHAIN_LINK_ADDED", "DECLARED_OUTCOME_WON"
    };
    private static final String RUN = "00000000-0000-0000-0000-0000000000aa";
    private static final String READ = "SCOPE_simulation:read";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** The Weaver's link escalates; every other bet reason is flat. */
    private ObjectNode engine() {
        var engine = objectMapper.createObjectNode();
        engine.put("maxEras", 5);
        var points = engine.putArray("reasonPoints");
        for (var reason : WIN_REASONS) {
            if (!"CHAIN_LINK_ADDED".equals(reason)) {
                points.addObject().put("scoreReason", reason).put("points", 6);
            }
        }
        var escalation = engine.putArray("escalations").addObject();
        escalation.put("name", "weaver-link");
        escalation.putArray("scoreReasons").add("CHAIN_LINK_ADDED");
        escalation.put("base", 6);
        escalation.put("increment", 3);
        escalation.putArray("resetOn").add("VOIDED");
        return engine;
    }

    private ObjectNode request() {
        var request = objectMapper.createObjectNode();
        request.set("engine", engine());
        var factions = request.putArray("factions");
        var rates = request.putArray("rates");
        for (var index = 0; index < FACTIONS.length; index++) {
            factions.add(FACTIONS[index]);
            for (var count : new int[] {3, 4, 5}) {
                var rate = rates.addObject();
                rate.put("playerCount", count);
                rate.put("faction", FACTIONS[index]);
                rate.putArray("betReasons")
                        .addObject()
                        .put("scoreReason", WIN_REASONS[index])
                        .put("rate", 0.5);
                rate.put("voidedRate", 0.2);
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
        return send(request, READ);
    }

    @Test
    void ratiosAndSensitivityComeBack() throws Exception {
        var request = request();
        request.putArray("sweep")
                .addObject()
                .put("parameter", "escalations.weaver-link.increment")
                .put("step", 1);

        send(request)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cells.length()").value(15))
                .andExpect(jsonPath("$.endings").doesNotExist())
                .andExpect(jsonPath("$.sensitivity.length()").value(30));
    }

    @Test
    void equalRequestsGiveByteIdenticalResponses() throws Exception {
        var request = request();
        request.putArray("sweep")
                .addObject()
                .put("parameter", "reasonPoints.ANNIHILATED_OUTCOME")
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
    void ratesThatSumAboveOneAreRefusedNamingTheItem() throws Exception {
        var request = request();
        ((ObjectNode) request.get("rates").get(3)).put("voidedRate", 0.7);

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("rates[3]"));
    }

    @Test
    void aSweepThatPushesTheRatesAboveOneIsRefusedNamingItsStep() throws Exception {
        var request = request();
        request.putArray("sweep")
                .addObject()
                .put("parameter", "rates.5.WEAVERS.voidedRate")
                .put("step", 0.4);

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("sweep[0].step"));
    }

    @Test
    void anOtherReasonThatBelongsToAnEscalationIsRefusedNamingIt() throws Exception {
        var request = request();
        var corruption =
                ((ObjectNode) request.get("engine")).withArray("escalations").addObject();
        corruption.put("name", "corrupt-run");
        corruption.putArray("scoreReasons").add("CORRUPTED_OPPONENT_CARD");
        corruption.put("base", 1);
        corruption.put("increment", 1);
        corruption.putArray("resetOn").add("VOIDED");
        ((ObjectNode) request.get("rates").get(0))
                .putArray("otherReasons")
                .addObject()
                .put("scoreReason", "CORRUPTED_OPPONENT_CARD")
                .put("perEra", 1);

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("rates[0].otherReasons[0].scoreReason"));
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
    void anEngineThatPricesNoReasonIsRefusedNamingIt() throws Exception {
        var request = request();
        ((ObjectNode) request.get("engine")).putArray("reasonPoints");

        send(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("engine.reasonPoints"));
    }

    @Test
    void anUnparsableSweepPathIsRefusedNamingIt() throws Exception {
        for (var path : new String[] {"escalations.nobody.base", "rates.3.NOBODY.voidedRate"}) {
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

    private ResultActions rescore(ObjectNode body, String scope) throws Exception {
        return mockMvc.perform(post("/api/v1/runs/" + RUN + "/rescorings")
                .with(jwt().authorities(new SimpleGrantedAuthority(scope)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ObjectNode rescoring() {
        var body = objectMapper.createObjectNode();
        body.set("engine", engine());
        body.put("variantLabel", "baseline");
        body.put("tableId", "table-1");
        return body;
    }

    @Test
    void rescoringAnUnknownRunIsNotFound() throws Exception {
        rescore(rescoring(), READ)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void rescoringWithoutTheReadScopeIsForbidden() throws Exception {
        rescore(rescoring(), "SCOPE_simulation:other").andExpect(status().isForbidden());
    }

    @Test
    void rescoringRejectsRepeatedAndMalformedSweepPathsAtTheirOriginalIndex() throws Exception {
        var repeated = rescoring();
        repeated.putArray("sweep").add("reasonPoints.ANNIHILATED_OUTCOME").add("reasonPoints.ANNIHILATED_OUTCOME");
        rescore(repeated, READ)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("sweep[1]"));

        var malformed = rescoring();
        malformed.putArray("sweep").add("maxEras");
        rescore(malformed, READ)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.field").value("sweep[0]"));
    }

    @Test
    void rescoringWithAnEngineThatPricesNoReasonIsRefusedNamingIt() throws Exception {
        var body = rescoring();
        ((ObjectNode) body.get("engine")).putArray("reasonPoints");

        rescore(body, READ)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("engine.reasonPoints"));
    }

    @Test
    void rescoringWithAPriceOutsideRangeIsRefusedNamingIt() throws Exception {
        var body = rescoring();
        ((ObjectNode) body.get("engine").get("reasonPoints").get(0)).put("points", 51);

        rescore(body, READ)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.field").value("engine.reasonPoints[0].points"));
    }
}
