package io.github.temporalrift.workbench.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.search.domain.campaign.Campaign;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignChange;
import io.github.temporalrift.workbench.search.domain.campaign.CampaignDefinition;
import io.github.temporalrift.workbench.search.domain.campaign.SearchAlgorithm;
import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;

/**
 * A designer follows a queued campaign: pauses it, changes how many games it plays at once, resumes it and reads the
 * decisions it has taken, without calling any endpoint. The campaign worker is off, so the page shows exactly what the
 * designer commands.
 */
@BrowserIntegrationTest
class CampaignBrowserIT extends DesignerBrowserTest {

    @Autowired
    private CampaignRepository campaigns;

    @Autowired
    private JdbcTemplate store;

    @BeforeEach
    @AfterEach
    void cleanCampaigns() {
        store.update("DELETE FROM campaign_round");
        store.update("DELETE FROM campaign_log_entry");
        store.update("DELETE FROM campaign_game");
        store.update("DELETE FROM campaign_wave");
        store.update("DELETE FROM campaign_candidate");
        store.update("DELETE FROM campaign");
    }

    @Test
    void aDesignerPausesAQueuedCampaignChangesItsGamesAtOnceResumesItAndReadsItsLog() {
        var campaignId = queuedCampaign();

        signInAndOpen(TestIssuer.DESIGNER, "/campaigns/" + campaignId);

        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Campaign browser-search")))
                .isVisible();
        assertThat(page.getByText("State: Queued")).isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Pause campaign"))
                .click();
        assertThat(page.getByText("State: Paused")).isVisible();
        assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Pause campaign")))
                .hasCount(0);

        page.getByLabel("Games at once", new Page.GetByLabelOptions().setExact(true))
                .last()
                .fill("2");
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Set games at once"))
                .click();
        assertThat(page.locator("dd")
                        .filter(new Locator.FilterOptions().setHasText("2").setHasNotText("h"))
                        .first())
                .isVisible();

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Resume campaign"))
                .click();
        assertThat(page.getByText("State: Running")).isVisible();

        var log = page.getByRole(
                AriaRole.TABLE, new Page.GetByRoleOptions().setName("Decisions, in the order they were taken"));
        assertThat(log.getByRole(AriaRole.ROW)).hasCount(3);
        assertThat(log.getByText("Generated")).isVisible();
        assertThat(log.getByText("A gate line fails (G-01)")).isVisible();
        assertThat(log.getByText("win-score-threshold = 22").first()).isVisible();
    }

    @Test
    void aDesignerStartsTheConfirmationOfAFinishedSearchAndIsToldWhyItCannotPlayYet() {
        var campaignId = searchedCampaign();

        signInAndOpen(TestIssuer.DESIGNER, "/campaigns/" + campaignId);

        assertThat(page.getByText("State: Search completed")).isVisible();
        assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Pause campaign")))
                .hasCount(0);
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Confirm short list"))
                .click();

        assertThat(page.getByRole(AriaRole.ALERT)
                        .filter(new Locator.FilterOptions().setHasText("confirm the campaign")))
                .isVisible();
        assertThat(page.getByText("State: Search completed")).isVisible();
        assertThat(page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Confirm short list")))
                .isEnabled();
    }

    @Test
    void theCampaignsPageListsTheCampaignAndLinksToIt() {
        var campaignId = queuedCampaign();

        signInAndOpen(TestIssuer.DESIGNER, "/campaigns");

        var table = page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Campaigns, newest first"));
        assertThat(table.getByText("browser-search")).isVisible();
        table.getByRole(AriaRole.LINK).first().click();
        page.waitForURL(url -> url.endsWith("/campaigns/" + campaignId));
    }

    /** A campaign whose search is complete with a short list, as the worker leaves it. */
    private UUID searchedCampaign() {
        var queued = queuedCampaign();
        var stored = campaigns.find(queued).orElseThrow();
        var searched = stored.campaign().start(Instant.now()).completeSearch(List.of("c-0001"));
        campaigns.apply(CampaignChange.of(stored, searched, Instant.now()));
        return queued;
    }

    private UUID queuedCampaign() {
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
                "browser-search",
                baseline,
                1,
                1,
                mapper.createArrayNode().add(policy),
                List.of(3),
                null,
                "7",
                "9",
                new SearchAlgorithm(SearchAlgorithm.Version.SUCCESSIVE_HALVING_V2, 8, 4, 2, 2));
        var now = Instant.now();
        var queued = Campaign.queued(UUID.randomUUID(), definition, 4, now);
        campaigns.create(UUID.randomUUID(), "h".repeat(64), queued, now);
        var overrides = new TreeMap<String, BigDecimal>();
        overrides.put("game.rules.win-score-threshold", new BigDecimal("22"));
        var candidate = new Candidate("c-0001", 0, List.of(1), overrides, "d".repeat(64), 0);
        var generated = new LogEntry(
                1,
                0,
                "c-0001",
                overrides,
                "d".repeat(64),
                List.of(),
                0,
                0,
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
                0,
                new LogEntry.Evaluation("REJECTED", null),
                LogEntry.Decision.DISCARDED,
                LogEntry.Reason.GATE_FAILS,
                LogEntry.Evidence.failingLines(List.of("G-01")));
        var stored = campaigns.find(queued.campaignId()).orElseThrow();
        campaigns.apply(
                CampaignChange.of(stored, queued, now).recording(List.of(candidate), List.of(generated, discarded)));
        return queued.campaignId();
    }
}
