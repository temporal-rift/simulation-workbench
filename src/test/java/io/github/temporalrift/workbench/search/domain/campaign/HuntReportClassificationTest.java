package io.github.temporalrift.workbench.search.domain.campaign;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.HuntReport.Classification;
import io.github.temporalrift.workbench.shared.game.Faction;

class HuntReportClassificationTest {

    @Test
    void atANormalRatioOfOneTheFlagStartsAtOneAndAQuarterAndTheExploitAtOneAndAHalf() {
        assertThat(Classification.of(1.24, 1.0)).isEqualTo(Classification.BALANCE_NOISE);
        assertThat(Classification.of(1.25, 1.0)).isEqualTo(Classification.FLAGGED);
        assertThat(Classification.of(1.49, 1.0)).isEqualTo(Classification.FLAGGED);
        assertThat(Classification.of(1.5, 1.0)).isEqualTo(Classification.EXPLOIT);
    }

    @Test
    void atANormalRatioOfTwoTheFlagStartsAtTwoAndAHalfAndTheExploitAtThree() {
        assertThat(Classification.of(2.49, 2.0)).isEqualTo(Classification.BALANCE_NOISE);
        assertThat(Classification.of(2.5, 2.0)).isEqualTo(Classification.FLAGGED);
        assertThat(Classification.of(2.99, 2.0)).isEqualTo(Classification.FLAGGED);
        assertThat(Classification.of(3.0, 2.0)).isEqualTo(Classification.EXPLOIT);
    }

    @Test
    void aStandardSeatBelowTheFairShareNeverLowersTheNormalRatioBelowOne() {
        assertThat(HuntReport.normalRatio(0.6)).isEqualTo(1.0);
        assertThat(Classification.of(1.2, 0.6)).isEqualTo(Classification.BALANCE_NOISE);
        assertThat(Classification.of(1.25, 0.6)).isEqualTo(Classification.FLAGGED);
    }

    @Test
    void aRatioBetweenNormalAndTheFlagLineIsBalanceNoiseNotAnExploit() {
        assertThat(Classification.of(1.2, 1.0)).isEqualTo(Classification.BALANCE_NOISE);
    }

    @Test
    void everyReportCarriesBothLimitations() {
        var report = HuntReport.unconfirmed(Faction.WEAVERS, 5, new HuntReport.Screening(64, 1.1, 0.3, 0.02));

        assertThat(report.limitations())
                .anySatisfy(text -> assertThat(text).contains("evidence, not a guarantee"))
                .anySatisfy(text -> assertThat(text).contains("bot strategies only"));
    }

    @Test
    void aHuntDefinitionNamesItsFactionAndExactlyOnePlayerCount() {
        var baseline = JsonMapper.builder().build().createObjectNode();
        var policies = JsonMapper.builder().build().createArrayNode();

        var one = List.of(5);
        var two = List.of(4, 5);
        assertThatThrownBy(() -> definition(baseline, policies, null, one))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> definition(baseline, policies, Faction.WEAVERS, two))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(definition(baseline, policies, Faction.WEAVERS, List.of(5)).isHunt())
                .isTrue();
    }

    private static CampaignDefinition definition(
            tools.jackson.databind.JsonNode baseline,
            tools.jackson.databind.JsonNode policies,
            Faction faction,
            List<Integer> playerCounts) {
        return new CampaignDefinition(
                "hunt",
                baseline,
                1,
                1,
                policies,
                playerCounts,
                null,
                "7",
                "9",
                HuntPlan.algorithm(5),
                CampaignKind.POLICY_HUNT,
                faction,
                1);
    }
}
