package io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.execution.domain.run.AttemptFailedException;
import io.github.temporalrift.workbench.execution.domain.run.FailureCode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActiveEvent;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.ActivistDeclarationMode;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardGrade;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.CardType;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.EligibleResolutionCard;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.EventOutcome;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.Phase;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.PhaseContext;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.RevealedIntel;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.RevealedProbabilityOutcome;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SpecialAction;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SpecialBudget;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionProgress;
import io.github.temporalrift.workbench.execution.infrastructure.adapter.out.lane.projection.model.SubmissionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.Faction;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;

class ObservationMapperTest {

    private static final int SEAT = 1;

    @Test
    void pendingHandSelectionBecomesTheSevenToFiveWindow() {
        var owed = ObservationMapper.owed(SEAT, ProjectionStates.ME, ProjectionStates.handSelection(2))
                .orElseThrow();

        assertThat(owed.era()).isEqualTo(2);
        var observation = owed.observation();
        assertThat(observation.seatIndex()).isEqualTo(SEAT);
        assertThat(observation.faction()).isEqualTo(Faction.ERASERS);
        assertThat(observation.window().key()).isEqualTo("era2/hand-selection");
        var window = (DecisionWindow.HandSelection) observation.window();
        assertThat(window.deal()).hasSize(7);
        assertThat(window.keepCount()).isEqualTo(5);
        assertThat(window.deal().get(0).category().name()).isEqualTo("PROBABILITY_SHIFTER");
        assertThat(window.deal().get(3).category().name()).isEqualTo("INFORMATION");
        assertThat(window.deal().get(4).category().name()).isEqualTo("DISRUPTION");
        assertThat(observation.otherPlayerIds())
                .containsExactlyInAnyOrder(ProjectionStates.OTHER_A, ProjectionStates.OTHER_B);
        assertThat(observation.events()).singleElement().satisfies(event -> {
            assertThat(event.eventId()).isEqualTo(ProjectionStates.EVENT);
            assertThat(event.outcomes()).extracting(OutcomeView::printedWeight).containsExactly(40, 60);
            assertThat(event.outcomes())
                    .allSatisfy(outcome -> assertThat(outcome.scannedWeight()).isNull());
        });
    }

    @Test
    void anAlreadyAcceptedHandSelectionOwesNothing() {
        var state = ProjectionStates.handSelection(1);
        state.getMySubmissions().add(ProjectionStates.accepted(SubmissionWindow.HAND_SELECTION, 1, null));

        assertThat(ObservationMapper.owed(SEAT, ProjectionStates.ME, state)).isEmpty();
    }

    @Test
    void theDeclarationWindowIsOwedOnlyToAnEligibleSeatThatHasNotDecided() {
        var state = ProjectionStates.base(Phase.ERA_START, 1);
        state.setPhaseContext(new PhaseContext(true, false));
        state.setMyEligibleDeclarationModes(Set.of(ActivistDeclarationMode.RALLY, ActivistDeclarationMode.MOMENTUM));

        var owed = ObservationMapper.owed(SEAT, ProjectionStates.ME, state).orElseThrow();
        var window = (DecisionWindow.Declaration) owed.observation().window();
        assertThat(window.eligibleModes()).extracting(Enum::name).containsExactly("RALLY", "MOMENTUM");

        state.setMyEligibleDeclarationModes(Set.of());
        assertThat(ObservationMapper.owed(SEAT, ProjectionStates.ME, state)).isEmpty();

        state.setMyEligibleDeclarationModes(Set.of(ActivistDeclarationMode.RALLY));
        state.getMySubmissions().add(ProjectionStates.accepted(SubmissionWindow.DECLARATION, 1, null));
        assertThat(ObservationMapper.owed(SEAT, ProjectionStates.ME, state)).isEmpty();
    }

    @Test
    void actionRoundOffersOnlyPlayableCardsWithTheShapeEachTypeRequires() {
        var hand = List.of(
                ProjectionStates.card(1, CardType.PUSH, CardGrade.II, true),
                ProjectionStates.card(2, CardType.SWING, CardGrade.I, true),
                ProjectionStates.card(3, CardType.SCAN, CardGrade.III, true),
                ProjectionStates.card(4, CardType.NULLIFY, CardGrade.II, true),
                ProjectionStates.card(5, CardType.DECOY, CardGrade.II, true),
                ProjectionStates.card(6, CardType.JAM, CardGrade.II, true),
                ProjectionStates.card(7, CardType.TRACE, CardGrade.I, false));
        var state = ProjectionStates.actionRound(1, 2, hand);

        var owed = ObservationMapper.owed(SEAT, ProjectionStates.ME, state).orElseThrow();

        assertThat(owed.round()).isEqualTo(2);
        var window = (DecisionWindow.ActionRound) owed.observation().window();
        assertThat(window.key()).isEqualTo("era1/round2/action");
        assertThat(window.cards())
                .extracting(card -> card.card().type().name() + ":" + card.shape() + ":" + card.targetCount())
                .containsExactly(
                        "PUSH:EVENT_OUTCOME:1",
                        "SWING:OUTCOME_PAIR:1",
                        "SCAN:EVENT_LIST:3",
                        "NULLIFY:PLAYER_LIST:2",
                        "DECOY:DISGUISE:1",
                        "JAM:PLAYER:1");
    }

    @Test
    void specialsExcludeDeclarationOnlyExhaustedAndJammedOnes() {
        var state = ProjectionStates.actionRound(1, 1, List.of());
        state.setMySpecialActions(
                List.of(SpecialAction.RALLY, SpecialAction.EXPOSE, SpecialAction.MOMENTUM, SpecialAction.ANNIHILATE));
        state.setMySpecialBudgets(List.of(new SpecialBudget(SpecialAction.ANNIHILATE, 0, 2)));

        var window = (DecisionWindow.ActionRound) ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .window();
        assertThat(window.specials())
                .extracting(special -> special.action().name() + ":" + special.shape())
                .containsExactly("EXPOSE:PLAYER");

        state.setMyJammedUntilRound(1);
        var jammed = (DecisionWindow.ActionRound) ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .window();
        assertThat(jammed.specials()).isEmpty();
    }

    @Test
    void aSeatThatAlreadySubmittedTheRoundOrIsNotPendingOwesNothing() {
        var state = ProjectionStates.actionRound(1, 1, List.of());
        state.getPhaseContext().setActionRoundProgress(new SubmissionProgress(1, 3, Set.of(ProjectionStates.OTHER_A)));

        assertThat(ObservationMapper.owed(SEAT, ProjectionStates.ME, state)).isEmpty();
    }

    @Test
    void paradoxResolutionOffersTheDealtCardsAgainstOnlyTheAffectedEvents() {
        var state = ProjectionStates.base(Phase.PARADOX_RESOLUTION, 3);
        state.getActiveEvents()
                .add(new ActiveEvent(
                        ProjectionStates.id(0xE1),
                        "Other",
                        ActiveEvent.CarryOverStateEnum.FRESH,
                        List.of(new EventOutcome(ProjectionStates.id(0xF1), "x", 100))));
        var context = new PhaseContext(false, true);
        context.setAffectedEventIds(List.of(ProjectionStates.EVENT));
        state.setPhaseContext(context);
        state.setMyEligibleResolutionCards(List.of(
                new EligibleResolutionCard(ProjectionStates.id(9), CardType.STABILIZE, CardGrade.II),
                new EligibleResolutionCard(ProjectionStates.id(10), CardType.DETONATE, CardGrade.II)));

        var owed = ObservationMapper.owed(SEAT, ProjectionStates.ME, state).orElseThrow();

        var window = (DecisionWindow.ParadoxResolution) owed.observation().window();
        assertThat(window.key()).isEqualTo("era3/paradox-resolution");
        assertThat(window.offer()).hasSize(2);
        assertThat(owed.observation().events())
                .singleElement()
                .satisfies(event -> assertThat(event.eventId()).isEqualTo(ProjectionStates.EVENT));
    }

    @Test
    void onlyTheCallersOwnProbabilityIntelBecomesAScannedWeight() {
        var state = ProjectionStates.handSelection(1);
        var older = new RevealedIntel(RevealedIntel.KindEnum.PROBABILITY, 1, ProjectionStates.EVENT);
        older.setOutcomes(List.of(
                new RevealedProbabilityOutcome(ProjectionStates.OUTCOME_LOW, 35, false, false),
                new RevealedProbabilityOutcome(ProjectionStates.OUTCOME_HIGH, 65, false, false)));
        var newer = new RevealedIntel(RevealedIntel.KindEnum.PROBABILITY, 2, ProjectionStates.EVENT);
        newer.setOutcomes(List.of(new RevealedProbabilityOutcome(ProjectionStates.OUTCOME_LOW, 30, false, false)));
        var influence = new RevealedIntel(RevealedIntel.KindEnum.INFLUENCE, 3, ProjectionStates.EVENT);
        state.setMyRevealedIntel(new ArrayList<>(List.of(older, newer, influence)));

        var event = ObservationMapper.owed(SEAT, ProjectionStates.ME, state)
                .orElseThrow()
                .observation()
                .events()
                .getFirst();

        assertThat(event.outcomes().get(0).scannedWeight()).isEqualTo(30);
        assertThat(event.outcomes().get(0).knownWeight()).isEqualTo(30);
        assertThat(event.outcomes().get(1).scannedWeight()).isEqualTo(65);
    }

    @Test
    void aGameWithoutAnAssignedFactionIsConfigurationDrift() {
        var state = ProjectionStates.handSelection(1);
        state.setMyFaction(null);

        assertThatThrownBy(() -> ObservationMapper.owed(SEAT, ProjectionStates.ME, state))
                .isInstanceOfSatisfying(
                        AttemptFailedException.class,
                        e -> assertThat(e.failure().code()).isEqualTo(FailureCode.CONFIGURATION_DRIFT));
    }

    @Test
    void anEndedGameIsObservedAsTerminalReadiness() {
        var state = ProjectionStates.base(Phase.GAME_ENDED, 5);

        var observation = ObservationMapper.terminal(SEAT, ProjectionStates.ME, state);

        assertThat(observation.window()).isEqualTo(new DecisionWindow.TerminalReadiness(5));
    }
}
