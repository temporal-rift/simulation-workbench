package io.github.temporalrift.workbench.browser;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;

import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.AriaRole;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.node.ObjectNode;

import io.github.temporalrift.workbench.execution.application.port.in.StartRunUseCase;
import io.github.temporalrift.workbench.execution.domain.port.out.CaseLane;
import io.github.temporalrift.workbench.execution.domain.port.out.GameSession;
import io.github.temporalrift.workbench.execution.domain.run.EndReason;
import io.github.temporalrift.workbench.execution.domain.run.FinalScore;
import io.github.temporalrift.workbench.execution.domain.run.WinType;
import io.github.temporalrift.workbench.execution.domain.run.Winner;
import io.github.temporalrift.workbench.experiment.ExperimentManifests;
import io.github.temporalrift.workbench.experiment.application.port.in.CreateExperimentUseCase;

/** A designer judges a finished run against the balance criterion without calling any endpoint. */
@BrowserIntegrationTest
class EvaluationBrowserIT extends DesignerBrowserTest {

    private static final String HUMAN_BALANCE = "Bot results say nothing about balance among human players.";

    @Autowired
    private CreateExperimentUseCase createExperiment;

    @Autowired
    private StartRunUseCase startRun;

    @Test
    void aDesignerOpensTheEvaluationPanelSeesTheVerdictAndFiltersToFailingLines() {
        lanes.script(this::erasersAlwaysWin);
        var runId = finishedRun();

        signInAndOpen(TestIssuer.DESIGNER, "/runs/" + runId);

        var panel = page.getByRole(AriaRole.HEADING, new Page.GetByRoleOptions().setName("Evaluation"))
                .locator("xpath=ancestor::section[1]");
        assertThat(panel.getByText(Pattern.compile("Verdict: Rejected"))).isVisible();
        assertThat(panel.getByText(HUMAN_BALANCE)).isVisible();
        var lines = panel.getByRole(AriaRole.TABLE, new Locator.GetByRoleOptions().setName("Evaluated lines"));
        assertThat(lines.getByRole(AriaRole.CELL, new Locator.GetByRoleOptions().setName("Meets")))
                .not()
                .hasCount(0);

        panel.getByLabel("Show").selectOption("FAILS");

        assertThat(lines.getByRole(AriaRole.CELL, new Locator.GetByRoleOptions().setName("Meets")))
                .hasCount(0);
        assertThat(lines.getByRole(AriaRole.CELL, new Locator.GetByRoleOptions().setName("Fails")))
                .not()
                .hasCount(0);
        assertThat(panel.getByText(HUMAN_BALANCE)).isVisible();
    }

    private UUID finishedRun() {
        var manifest = (ObjectNode) ExperimentManifests.threePlayerTwoVariants().deepCopy();
        var seeds = manifest.putArray("seeds");
        List.of("1", "2", "3", "4").forEach(seeds::add);
        var experimentId = createExperiment
                .handle(new CreateExperimentUseCase.Command(UUID.randomUUID(), manifest))
                .experimentId();
        var runId = startRun.handle(new StartRunUseCase.Command(experimentId, UUID.randomUUID()))
                .run()
                .runId();
        executePendingCases();
        return runId;
    }

    /** Erasers win every game wherever they sit, so their share of wins is three times an average seat. */
    private GameSession.AuthoritativeEnding erasersAlwaysWin(CaseLane.CaseContext context) {
        var scores = context.seats().stream()
                .map(seat -> new FinalScore(seat.seatIndex(), seat.faction(), 30 - seat.seatIndex() * 4))
                .toList();
        var winner = context.seats().stream()
                .filter(seat -> "ERASERS".equals(seat.faction()))
                .findFirst()
                .orElseThrow();
        return new GameSession.AuthoritativeEnding(
                EndReason.WIN_CONDITION_MET,
                List.of(new Winner(winner.seatIndex(), winner.faction(), WinType.SCORE_THRESHOLD)),
                scores,
                3);
    }
}
