package io.github.temporalrift.workbench.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.support.ScriptedLanes;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

/**
 * What a browser may show of a replay, asserted on the page and on every response behind it. Runs are made
 * directly, so these tests are about the boundary and not about driving the forms.
 */
@BrowserIntegrationTest
class ReplayBoundaryIT extends DesignerBrowserTest {

    /** The ids a seat's hand is made of are (seat, slot) pairs, so one seat's id shows up in no other seat's view. */
    private static final String SEAT_0_HAND = new UUID(0, 1).toString();

    private static final String SEAT_1_HAND = new UUID(1, 1).toString();
    private static final String SEAT_2_HAND = new UUID(2, 1).toString();
    private static final String EARNED_WEIGHT = "scannedWeight";

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    /** The bodies of the replay responses the page received, by the perspective they were requested for. */
    private final List<String> playerBodies = new CopyOnWriteArrayList<>();

    private final List<String> observerBodies = new CopyOnWriteArrayList<>();

    private UUID runId;
    private UUID caseId;

    @BeforeEach
    void recordReplayResponses() {
        playerBodies.clear();
        observerBodies.clear();
        page.route("**/api/v1/runs/*/cases/*/replay**", route -> {
            var response = route.fetch();
            var observer = route.request().url().contains("perspective=OBSERVER");
            (observer ? observerBodies : playerBodies).add(response.text());
            route.fulfill(new Route.FulfillOptions().setResponse(response));
        });
    }

    @Test
    void aPlayerSeesNeitherAnOpponentsHandNorFactsTheSeatHasNotEarned() {
        lanes.script(ScriptedLanes.seatZeroEarnsExactWeightAtTheTerminalWindow());
        runToCompletion();
        signInAndOpen(TestIssuer.DESIGNER, casePath());

        showPlayer(1);

        var view = page.locator(".player-replay");
        assertThat(view).containsText(SEAT_1_HAND);
        assertThat(view).not().containsText(SEAT_0_HAND);
        assertThat(view).not().containsText(SEAT_2_HAND);
        assertThat(view).not().containsText(EARNED_WEIGHT);
        assertThat(page.getByText("Observer evidence", new Page.GetByTextOptions().setExact(true)))
                .hasCount(0);
        org.assertj.core.api.Assertions.assertThat(observerBodies).isEmpty();
        org.assertj.core.api.Assertions.assertThat(playerBodies)
                .isNotEmpty()
                .allSatisfy(body -> org.assertj.core.api.Assertions.assertThat(body)
                        .doesNotContain(SEAT_0_HAND, SEAT_2_HAND, EARNED_WEIGHT));
    }

    @Test
    void earnedInformationAppearsOnlyAtTheStepWhereTheSeatEarnedIt() {
        lanes.script(ScriptedLanes.seatZeroEarnsExactWeightAtTheTerminalWindow());
        runToCompletion();
        signInAndOpen(TestIssuer.DESIGNER, casePath());

        showPlayer(0);

        var steps = page.locator(".player-replay ol.steps > li");
        assertThat(steps).hasCount(2);
        assertThat(steps.first()).not().containsText(EARNED_WEIGHT);
        assertThat(steps.last()).containsText(EARNED_WEIGHT);
        assertThat(page.locator(".player-replay")).not().containsText(SEAT_1_HAND);
    }

    @Test
    void anObserverReplayIsDeniedAndLabelledWithoutTheObserveScope() {
        runToCompletion();
        signInAndOpen(TestIssuer.ANALYST, casePath());

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("View as observer…"))
                .click();

        assertThat(page.locator(".observer-denied")).containsText("Observer access denied.");
        assertThat(page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Observer evidence")))
                .hasCount(0);
        org.assertj.core.api.Assertions.assertThat(observerBodies).isEmpty();
    }

    @Test
    void anObserverReplayIsLabelledAndSwitchingToASeatLeavesNothingOfIt() {
        lanes.script(ScriptedLanes.seatZeroEarnsExactWeightAtTheTerminalWindow());
        runToCompletion();
        signInAndOpen(TestIssuer.DESIGNER, casePath());

        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("View as observer…"))
                .click();
        var observer = page.locator(".observer-replay");
        assertThat(observer).containsText("never feeds bot policies");
        assertThat(observer).containsText(SEAT_0_HAND);
        assertThat(observer).containsText(SEAT_1_HAND);
        assertThat(observer).containsText(SEAT_2_HAND);

        showPlayer(2);

        assertThat(page.locator(".observer-replay")).hasCount(0);
        assertThat(page.locator(".player-replay")).containsText(SEAT_2_HAND);
        assertThat(page.locator("main")).not().containsText(SEAT_0_HAND);
        assertThat(page.locator("main")).not().containsText(SEAT_1_HAND);
        assertThat(page.locator("main")).not().containsText(EARNED_WEIGHT);
    }

    @Test
    void aRunWithAFailedCaseListsTheFailureAndMarksTheDataPartial() {
        var opens = new AtomicInteger();
        lanes.script(new ScriptedLanes.Script() {
            @Override
            public io.github.temporalrift.workbench.execution.domain.port.out.GameSession.AuthoritativeEnding ending(
                    CaseLane.CaseContext context) {
                return ScriptedLanes.decisive(context);
            }

            /** The first case never gets a game, on any of its attempts. */
            @Override
            public void onOpen(CaseLane.CaseContext context) {
                if (opens.getAndIncrement() < 2) {
                    throw new AttemptFailedException(FailureCode.EXECUTION_FAILED, "the lane dropped");
                }
            }
        });
        runToCompletion();
        signInAndOpen(TestIssuer.READER, "/runs/" + runId);

        assertThat(page.getByText(Pattern.compile("Partial: 2 of 3 cases have succeeded")))
                .isVisible();
        var failures = page.locator("ul.failures");
        assertThat(failures).containsText("EXECUTION_FAILED");
        assertThat(failures).containsText("the lane dropped");
        failures.getByRole(AriaRole.LINK).first().click();
        assertThat(page.getByText(Pattern.compile("This case has no result"))).isVisible();
    }

    private void runToCompletion() {
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(
                        UUID.randomUUID(), ExperimentManifests.threePlayerSingleSet()))
                .experimentId();
        runId = startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
        executePendingCases();
        caseId = jdbc.queryForList("SELECT case_id FROM run_case WHERE run_id = ? ORDER BY ordinal", UUID.class, runId)
                .getFirst();
    }

    private String casePath() {
        return "/runs/%s/cases/%s".formatted(runId, caseId);
    }

    private void showPlayer(int seatIndex) {
        page.locator("section[aria-labelledby=replay-heading] select").selectOption(String.valueOf(seatIndex));
        page.getByRole(AriaRole.BUTTON, new Page.GetByRoleOptions().setName("View as player"))
                .click();
        Locator view =
                page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Player view: seat " + seatIndex));
        assertThat(view).isVisible();
    }
}
