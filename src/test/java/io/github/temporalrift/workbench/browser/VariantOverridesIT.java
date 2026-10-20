package io.github.temporalrift.workbench.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import com.microsoft.playwright.options.SelectOption;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.domain.run.BundleDigests;
import io.github.temporalrift.workbench.experiment.VariantRulesFixtures;

/** A designer compares the baseline with a candidate that overrides a parameter of it, typing no digest. */
@BrowserIntegrationTest
class VariantOverridesIT extends DesignerBrowserTest {

    private static final String THRESHOLD = "game.rules.win-score-threshold";
    private static final Pattern DIGEST = Pattern.compile("^[a-f0-9]{64}$");

    @Test
    void aDesignerOverridesAParameterSeesTheDigestOfItsRulesAndIsRefusedAnOutOfRangeValueOnItsRow() {
        var baseline = VariantRulesFixtures.baseline();
        lanes.serve(new BundleDigests(baseline.digest(), "b".repeat(64)));
        signInAndOpen(TestIssuer.DESIGNER, "/experiments/new");
        button("Start from Rules variant comparison").click();
        page.getByLabel("Experiment name").fill("threshold 22 against baseline");
        page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Bundle"))
                .nth(1)
                .selectOption(new SelectOption().setIndex(1));

        // An override of a parameter of the space, with its baseline, range and step shown.
        overrides(2)
                .getByRole(AriaRole.BUTTON, new Locator.GetByRoleOptions().setName("Add override"))
                .click();
        var value = page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Value of parameter 1"));
        assertThat(page.getByText("Baseline 20, from 16 to 26 in steps of 1.")).isVisible();
        value.fill("22");

        // The digest of the rules the override makes, not the baseline's.
        var digest = overrides(2).locator("code");
        assertThat(digest).hasText(DIGEST);
        assertThat(digest).not().hasText(baseline.digest());

        // A value outside the range is refused by the service, on the row that holds it.
        value.fill("27");
        assertThat(value).hasAttribute("aria-invalid", "true");
        assertThat(page.getByText(Pattern.compile("win-score-threshold must be from 16 to 26: 27")))
                .isVisible();

        // Fixed, the variant freezes with the rules its digest names.
        value.fill("22");
        assertThat(value).not().hasAttribute("aria-invalid", "true");
        assertThat(digest).hasText(DIGEST);
        button("Preview matrix").click();
        assertThat(page.getByText(Pattern.compile("cases in total"))).isVisible();
        button("Freeze this experiment…").click();
        button("Confirm and freeze").click();
        assertThat(page.getByRole(
                        AriaRole.HEADING,
                        new Page.GetByRoleOptions()
                                .setName("threshold 22 against baseline")
                                .setExact(true)))
                .isVisible();

        var mapper = new ObjectMapper();
        var stored = jdbc.queryForMap("SELECT experiment_id, manifest_json FROM experiment");
        var manifest = mapper.readTree((String) stored.get("manifest_json"));
        Assertions.assertThat(manifest.at("/variants/0/effectiveRulesDigest").asString())
                .isEqualTo(baseline.digest());
        Assertions.assertThat(
                        manifest.at("/variants/1/overrides").get(THRESHOLD).asInt())
                .isEqualTo(22);
        Assertions.assertThat(manifest.at("/variants/1/parameterSpaceVersion").asInt())
                .isEqualTo(1);
        Assertions.assertThat(jdbc.queryForObject(
                        "SELECT document FROM experiment_baseline WHERE experiment_id = ?",
                        String.class,
                        stored.get("experiment_id")))
                .isEqualTo(VariantRulesFixtures.baselineText());
    }

    private Locator overrides(int variant) {
        return page.locator(
                "fieldset.overrides", new Page.LocatorOptions().setHasText("Overrides of variant " + variant));
    }

    private Locator button(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name));
    }
}
