package io.github.temporalrift.workbench.browser;

import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import io.github.temporalrift.workbench.execution.application.port.in.RunBatchUseCase;
import io.github.temporalrift.workbench.execution.application.port.in.RunReproductionUseCase;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;

/**
 * A designer's browser against the running application. The browser does everything a designer does; the
 * test thread only plays the part of the worker, executing the cases and reproductions the browser asked for.
 */
abstract class DesignerBrowserTest {

    static final Duration UI_WAIT = Duration.ofSeconds(30);

    private static final String WORKER = "browser-worker";
    private static final String DRAFT_KEY = "workbench.experiment-draft.v1";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Playwright playwright;
    private static Browser browser;

    @DynamicPropertySource
    static void identityProvider(DynamicPropertyRegistry registry) {
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", TestIssuer::issuerUri);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private RunBatchUseCase batch;

    @Autowired
    private RunReproductionUseCase reproductions;

    @Autowired
    protected ScriptedLanes lanes;

    @Autowired
    protected JdbcTemplate jdbc;

    /** What the designer was looking at when a test failed, since a failed locator says only what it did not find. */
    @RegisterExtension
    final TestWatcher onFailure = new TestWatcher() {
        @Override
        public void testFailed(ExtensionContext extension, Throwable cause) {
            System.err.println("Page at failure: " + lastView);
            consoleErrors.forEach(message -> System.err.println("Browser console: " + message));
        }
    };

    private String lastView = "";
    private final List<String> consoleErrors = new CopyOnWriteArrayList<>();
    private BrowserContext context;
    protected Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }

    @BeforeEach
    void openPage() {
        cleanStore();
        lanes.reset();
        context = browser.newContext();
        context.setDefaultTimeout((double) UI_WAIT.toMillis());
        page = context.newPage();
        consoleErrors.clear();
        page.onConsoleMessage(message -> {
            if ("error".equals(message.type())) {
                consoleErrors.add(message.text());
            }
        });
    }

    @AfterEach
    void closePage() {
        lastView = page.url() + System.lineSeparator() + page.locator("body").innerText();
        context.close();
        cleanStore();
    }

    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** Starts the tab with `manifest` filled into the experiment form, as a designer who had typed it. */
    protected void startWithDraft(JsonNode manifest) {
        var stored = MAPPER.writeValueAsString(DraftJson.storedDraft(manifest));
        // Seeded once per tab, so freezing (which clears the draft) is not undone by the next page load.
        context.addInitScript(
                "if (location.origin === '%s' && !sessionStorage.getItem('seeded')) {".formatted(baseUrl())
                        + " sessionStorage.setItem('seeded', '1'); sessionStorage.setItem('%s', %s); }"
                                .formatted(DRAFT_KEY, stored));
    }

    /** Opens `path` as `subject`, signing in at the identity provider first. */
    protected void signInAndOpen(String subject, String path) {
        page.navigate(baseUrl() + path);
        page.getByRole(com.microsoft.playwright.options.AriaRole.BUTTON, new Page.GetByRoleOptions().setName("Sign in"))
                .click();
        page.locator("input[name=username]").fill(subject);
        page.locator("input[type=submit]").click();
        page.waitForURL(url -> url.startsWith(baseUrl()) && !url.contains("/auth/callback"));
    }

    /** The run a designer started from the browser, once the service has it. */
    protected UUID awaitStartedRun() {
        return await().atMost(UI_WAIT)
                .until(() -> jdbc.queryForList("SELECT run_id FROM run", UUID.class), runs -> !runs.isEmpty())
                .getFirst();
    }

    /** Plays the worker: starts queued runs, executes every pending case and settles the run. */
    protected void executePendingCases() {
        batch.maintain();
        while (batch.runNextCase(WORKER)) {
            // every case in turn
        }
        batch.maintain();
    }

    /** Plays the worker for the reproduction a designer asked for. */
    protected void executeReproductions() {
        await().atMost(UI_WAIT).until(() -> reproductions.runNext(WORKER));
    }

    private void cleanStore() {
        for (var table : new String[] {
            "reproduction",
            "analysis_comparison",
            "analysis_case_fact",
            "evidence_event",
            "evidence_step",
            "evidence_source_offset",
            "case_evidence",
            "evidence_artifact",
            "case_command",
            "case_attempt",
            "run_case",
            "run_command",
            "run",
            "idempotency_key",
            "experiment"
        }) {
            jdbc.update("DELETE FROM " + table);
        }
    }
}
