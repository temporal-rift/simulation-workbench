package io.github.temporalrift.workbench.search.infrastructure.adapter.in.rest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignKind;
import io.github.temporalrift.workbench.search.domain.campaign.HuntPlan;
import io.github.temporalrift.workbench.search.domain.campaign.HuntReport;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.infrastructure.adapter.in.rest.v1.model.HuntClassification;

/** How a hunt crosses the REST boundary: its kind, its faction and its report. */
class CampaignApiMapperHuntTest {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final Instant NOW = Instant.parse("2026-11-26T10:00:00Z");

    private final CampaignApiMapper mapper = new CampaignApiMapper(MAPPER);

    private static Campaign hunt(HuntReport report) {
        var baseline = MAPPER.createObjectNode();
        baseline.putObject("rulesArtifact").put("digest", "d".repeat(64));
        baseline.putObject("contentArtifact").put("digest", "c".repeat(64));
        var definition = new CampaignDefinition(
                "weavers-5p",
                baseline,
                1,
                1,
                MAPPER.createArrayNode(),
                List.of(5),
                null,
                "7",
                "9",
                HuntPlan.algorithm(5),
                CampaignKind.POLICY_HUNT,
                Faction.WEAVERS,
                1);
        return Campaign.queued(UUID.randomUUID(), definition, 4, NOW).withHuntReport(report);
    }

    @Test
    void aHuntReadsWithItsKindFactionAndSpace() {
        var api = mapper.toApi(hunt(null));

        assertThat(api.getKind().name()).isEqualTo("POLICY_HUNT");
        assertThat(api.getFaction().getValue()).isEqualTo("WEAVERS");
        assertThat(api.getPolicyParameterSpaceVersion()).isEqualTo(1);
        assertThat(api.getHuntReport()).isNull();
        assertThat(api.getPlayerCounts()).hasSize(1);
        assertThat(api.getAlgorithm().getInitialCandidates()).isEqualTo(64);
        assertThat(api.getAlgorithm().getEta()).isEqualTo(4);
    }

    @Test
    void aConfirmedReportCrossesWithItsRatiosParametersAndLimitations() {
        var report = new HuntReport(
                Faction.WEAVERS,
                5,
                new HuntReport.Screening(64, 1.31, 0.31, 0.04),
                HuntReport.Classification.FLAGGED,
                new HuntReport.Confirmed(1.34, 1.1, 1.58, 1.0, 1.0, Map.of("affinity.card.WEAVERS.SCAN", 8)),
                HuntReport.STANDARD_STATEMENTS);

        var api = mapper.toApi(hunt(report)).getHuntReport();

        assertThat(api.getClassification()).isEqualTo(HuntClassification.FLAGGED);
        assertThat(api.getScreening().getCandidates()).isEqualTo(64);
        assertThat(api.getScreening().getNoise()).isEqualByComparingTo("0.31");
        assertThat(api.getScreening().getShareAboveFlagLine()).isEqualByComparingTo("0.04");
        assertThat(api.getConfirmation().getCandidateRatio()).isEqualByComparingTo("1.34");
        assertThat(api.getConfirmation().getCandidateInterval().getUpper()).isEqualByComparingTo("1.58");
        assertThat(api.getConfirmation().getParameters()).containsEntry("affinity.card.WEAVERS.SCAN", 8);
        assertThat(api.getLimitations()).hasSize(2);
    }
}
