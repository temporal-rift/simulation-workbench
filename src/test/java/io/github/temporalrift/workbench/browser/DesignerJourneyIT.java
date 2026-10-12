package io.github.temporalrift.workbench.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.assertions.LocatorAssertions;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.experiment.ExperimentManifests;

/** The whole research loop, driven the way a designer drives it: no endpoint is called by the test. */
@BrowserIntegrationTest
class DesignerJourneyIT extends DesignerBrowserTest {

    @Test
    void aDesignerFreezesRunsComparesInspectsAndReproducesACaseInTheBrowser() throws Exception {
        startWithDraft(ExperimentManifests.threePlayerTwoVariants());
        signInAndOpen(TestIssuer.DESIGNER, "/experiments/new");

        // Freeze: preview the exact matrix, then confirm the digest that was shown.
        click("Preview matrix");
        assertThat(heading("Matrix preview")).isVisible();
        click("Freeze this experiment…");
        assertThat(page.getByText(Pattern.compile("freeze exactly 6 cases"))).isVisible();
        click("Confirm and freeze");

        // Run: the browser launches it, a worker executes it, the page follows it to the end.
        click("Launch a run");
        var runId = awaitStartedRun();
        executePendingCases();
        assertThat(page.getByText("State: Completed")).isVisible(longWait());
        assertThat(page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Case counts")))
                .containsText("6");

        // Report: complete, with an export that is exactly what the service returned.
        click(AriaRole.LINK, "Report");
        assertThat(heading(Pattern.compile("Report for run"))).isVisible();
        assertThat(page.getByText(Pattern.compile("Partial report"))).hasCount(0);
        var served = new AtomicReference<byte[]>();
        page.route("**/api/v1/runs/*/report", route -> {
            var response = route.fetch();
            if ("text/csv".equals(route.request().headerValue("accept"))) {
                served.set(response.body());
            }
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        });
        var download = page.waitForDownload(() -> click("Export CSV"));
        org.assertj.core.api.Assertions.assertThat(Files.readAllBytes(download.path()))
                .isNotEmpty()
                .isEqualTo(served.get());

        // Compare: the two variants of the run, by the identifiers the page shows.
        click(AriaRole.LINK, "Comparisons");
        page.getByLabel("Baseline run ID").fill(runId.toString());
        page.getByLabel("Baseline variant").fill("baseline");
        page.getByLabel("Candidate run ID").fill(runId.toString());
        page.getByLabel("Candidate variant").fill("candidate");
        click("Compare");
        assertThat(heading("Comparison of baseline and candidate")).isVisible();
        assertThat(page.getByText("Declared differences")).isVisible();

        // Inspect: from the run to one of its cases and its replay as a player.
        click(AriaRole.LINK, "baseline");
        page.getByRole(AriaRole.TABLE, new Page.GetByRoleOptions().setName("Cases of this run"))
                .getByRole(AriaRole.LINK)
                .first()
                .click();
        assertThat(heading(Pattern.compile("^Case "))).isVisible();
        click("View as player");
        assertThat(heading("Player view: seat 0")).isVisible();

        // Reproduce: queued first, then exactly the recording.
        click("Reproduce this case");
        assertThat(page.getByText(Pattern.compile("queued behind other work"))).isVisible();
        executeReproductions();
        assertThat(page.getByText(Pattern.compile("matched the recording exactly")))
                .isVisible(longWait());
    }

    private com.microsoft.playwright.Locator heading(String name) {
        return page.getByRole(
                AriaRole.HEADING, new Page.GetByRoleOptions().setName(name).setExact(true));
    }

    private com.microsoft.playwright.Locator heading(Pattern name) {
        return page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName(name));
    }

    private void click(String button) {
        click(AriaRole.BUTTON, button);
    }

    private void click(AriaRole role, String name) {
        page.getByRole(role, new Page.GetByRoleOptions().setName(name)).click();
    }

    private static LocatorAssertions.IsVisibleOptions longWait() {
        return new LocatorAssertions.IsVisibleOptions().setTimeout(UI_WAIT.toMillis());
    }
}
