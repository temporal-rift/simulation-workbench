package io.github.temporalrift.workbench.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.ExecutionTestConfiguration;

/** A designer sets up a rules comparison by choosing a template and bundles, typing no digest. */
@BrowserIntegrationTest
class GuidedSetupIT extends DesignerBrowserTest {

    private static final Pattern TYPED_PROVENANCE =
            Pattern.compile("digest|revision|version|module", Pattern.CASE_INSENSITIVE);

    @Test
    void aDesignerFreezesATwoVariantComparisonWithoutTypingADigest() {
        signInAndOpen(TestIssuer.DESIGNER, "/experiments/new");

        button("Start from Rules variant comparison").click();
        page.getByLabel("Experiment name").fill("threshold 20 against 22");

        assertThat(page.getByRole(AriaRole.TEXTBOX, new Page.GetByRoleOptions().setName(TYPED_PROVENANCE)))
                .hasCount(0);
        assertThat(page.getByRole(AriaRole.COMBOBOX, new Page.GetByRoleOptions().setName("Bundle")))
                .hasCount(2);
        button("Preview matrix").click();
        assertThat(page.getByText(Pattern.compile("110 cases in total"))).isVisible();
        button("Freeze this experiment…").click();
        button("Confirm and freeze").click();
        assertThat(page.getByRole(
                        AriaRole.HEADING,
                        new Page.GetByRoleOptions()
                                .setName("threshold 20 against 22")
                                .setExact(true)))
                .isVisible();

        var manifest =
                new ObjectMapper().readTree(jdbc.queryForObject("SELECT manifest_json FROM experiment", String.class));
        Assertions.assertThat(manifest.at("/variants/0/effectiveRulesDigest").asString())
                .isEqualTo(ExecutionTestConfiguration.DEFAULT_BUNDLE.rulesDigest());
        Assertions.assertThat(manifest.at("/variants/1/effectiveRulesDigest").asString())
                .isEqualTo(ExecutionTestConfiguration.CANDIDATE_BUNDLE.rulesDigest());
        Assertions.assertThat(manifest.at("/factionSets").size()).isEqualTo(16);
    }

    private Locator button(String name) {
        return page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName(name));
    }
}
