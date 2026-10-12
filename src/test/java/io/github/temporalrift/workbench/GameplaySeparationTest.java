package io.github.temporalrift.workbench;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * What keeps the workbench from altering ordinary gameplay or its privacy: it reaches the gameplay services only
 * through generated clients in one adapter package, carries no copy of their code, and gives bot policies nothing
 * but what a seat is entitled to observe.
 */
@AnalyzeClasses(packages = "io.github.temporalrift.workbench", importOptions = ImportOption.DoNotIncludeTests.class)
public class GameplaySeparationTest {

    private static final String LANE_ADAPTER =
            "io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane";

    @ArchTest
    static final ArchRule no_gameplay_service_code_is_copied_or_shared = noClasses()
            .that()
            .resideInAPackage("io.github.temporalrift.workbench..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.temporalrift.game..",
                    "io.github.temporalrift.timeline..",
                    "io.github.temporalrift.read..")
            .as("The workbench depends on published contracts only, never on a gameplay service's own code");

    @ArchTest
    static final ArchRule generated_gameplay_clients_are_used_only_by_the_lane_adapter = noClasses()
            .that()
            .resideOutsideOfPackage(LANE_ADAPTER + "..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    LANE_ADAPTER + ".session..",
                    LANE_ADAPTER + ".action..",
                    LANE_ADAPTER + ".scoring..",
                    LANE_ADAPTER + ".projection..",
                    LANE_ADAPTER + ".control..")
            .as("Only the lane adapter calls game, timeline and read services, and only through generated clients");

    @ArchTest
    static final ArchRule bot_policies_see_neither_evidence_nor_observer_data = noClasses()
            .that()
            .resideInAPackage("io.github.temporalrift.workbench.policy..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "io.github.temporalrift.workbench.execution..",
                    "io.github.temporalrift.workbench.analysis..",
                    "io.github.temporalrift.workbench.experiment..")
            .as("A bot policy decides from its entitled observation alone, never from evidence or analysis");
}
