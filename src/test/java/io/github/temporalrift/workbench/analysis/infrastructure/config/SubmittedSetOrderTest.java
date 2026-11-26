package io.github.temporalrift.workbench.analysis.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.Escalation;

class SubmittedSetOrderTest {

    @Test
    void aSetOfARequestKeepsTheOrderTheClientSubmittedIt() {
        var builder = JsonMapper.builder();
        new AnalysisConfiguration().analysisKeepsSubmittedSetOrder().customize(builder);
        var reasons = List.of("MIMIC_NEVER_TRACED", "CHAIN_LINK_ADDED", "ANNIHILATED_OUTCOME", "ZZ_LAST", "AA_FIRST");
        var json = "{\"name\":\"run\",\"scoreReasons\":[\"%s\"],\"base\":1,\"increment\":1,\"resetOn\":[\"VOIDED\"]}"
                .formatted(String.join("\",\"", reasons));

        var escalation = builder.build().readValue(json, Escalation.class);

        assertThat(escalation.getScoreReasons()).isInstanceOf(Set.class).containsExactlyElementsOf(reasons);
    }
}
