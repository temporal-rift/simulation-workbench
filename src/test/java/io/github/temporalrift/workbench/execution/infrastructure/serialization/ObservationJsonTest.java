package io.github.temporalrift.workbench.execution.infrastructure.serialization;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.observation.Band;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.HandCard;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableSpecial;
import io.github.temporalrift.workbench.policy.domain.observation.PublicBand;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

class ObservationJsonTest {

    @Test
    void theRetainedActionRoundObservationKeepsEveryHandCardWithItsPlayability() {
        var push = PolicyFixtures.card(1, CardType.PUSH);
        var trace = PolicyFixtures.card(2, CardType.TRACE);
        var window = new DecisionWindow.ActionRound(
                1,
                2,
                List.of(new HandCard(push, true), new HandCard(trace, false)),
                List.of(new PlayableCard(push, TargetShape.EVENT_OUTCOME, 1)),
                List.of());

        var retained = JsonMapper.builder()
                .build()
                .readTree(ObservationJson.of(PolicyFixtures.observation(Faction.ERASERS, window)));

        assertThat(retained.at("/window/hand"))
                .extracting(card -> card.at("/card/type").asString() + ":"
                        + card.at("/playable").asBoolean())
                .containsExactly("PUSH:true", "TRACE:false");
        assertThat(retained.at("/window/cards")).hasSize(1);
    }

    @Test
    void anObservationRecordedBeforeBoardFactsReadsBackAndKeepsItsText() throws Exception {
        var recorded = new String(
                        getClass()
                                .getResourceAsStream("/observation/pre-board-action-round.json")
                                .readAllBytes(),
                        StandardCharsets.UTF_8)
                .strip();

        var observation = ObservationJson.parse(recorded);

        assertThat(observation.board()).isEqualTo(Board.EMPTY);
        assertThat(observation.events().getFirst().outcomes())
                .allSatisfy(outcome -> assertThat(outcome.band()).isNull());
        assertThat(observation).isEqualTo(PolicyFixtures.actionRound(Faction.PROPHETS));
        assertThat(ObservationJson.of(observation)).isEqualTo(recorded);
        assertThat(observation.rules()).isNull();
        assertThat(observation.tableRules()).isEqualTo(TableRules.DEFAULTS);
    }

    @Test
    void aSpecialMarkedUnavailableReadsBackEqualAndAnUnmarkedOneKeepsItsEarlierText() {
        var plain = PolicyFixtures.actionRound(Faction.ERASERS);
        assertThat(ObservationJson.of(plain)).doesNotContain("unavailable");

        var window = (DecisionWindow.ActionRound) plain.window();
        var marked = new DecisionWindow.ActionRound(
                window.era(),
                window.round(),
                window.hand(),
                window.cards(),
                List.of(new PlayableSpecial(
                        SpecialAction.ANNIHILATE, TargetShape.EVENT_OUTCOME, "NOT_ELIGIBLE_THIS_ROUND")));
        var observation = new EntitledObservation(
                0, Faction.ERASERS, plain.events(), plain.otherPlayerIds(), marked, plain.board());

        var text = ObservationJson.of(observation);

        assertThat(text).contains("\"unavailableReason\":\"NOT_ELIGIBLE_THIS_ROUND\"");
        assertThat(ObservationJson.parse(text)).isEqualTo(observation);
    }

    @Test
    void anObservationWithTableRulesReadsBackEqual() {
        var rules = new TableRules(false, -1, -1, -2, true);
        var observation = PolicyFixtures.actionRound(Faction.ERASERS).withRules(rules);

        var text = ObservationJson.of(observation);

        assertThat(ObservationJson.parse(text)).isEqualTo(observation);
        assertThat(text).contains("\"rules\"").doesNotContain("tableRules");
    }

    @Test
    void theFactsOfTheSpreadBotReadBackEqualAndAreWrittenOnlyWhenKnown() {
        var plain = PolicyFixtures.actionRound(Faction.ERASERS);
        assertThat(ObservationJson.of(plain))
                .doesNotContain("stalled")
                .doesNotContain("confirmedEventIds")
                .doesNotContain("cascadedEventIds")
                .doesNotContain("scores")
                .doesNotContain("weaverChainLength");

        var stalled =
                new EventView(PolicyFixtures.EVENT_A, plain.events().getFirst().outcomes(), true);
        var board = new Board(
                List.of(),
                null,
                List.of(),
                new Board.ChainView(Board.ChainStatus.ACTIVE, 2, null, List.of(PolicyFixtures.EVENT_B)),
                List.of(),
                List.of(PolicyFixtures.EVENT_C),
                new Board.Scores(7, List.of(3, 9)));
        var observation = new EntitledObservation(
                        0, Faction.ERASERS, List.of(stalled), plain.otherPlayerIds(), plain.window(), board)
                .withRules(new TableRules(true, 0, -1, -2, false, 5, 4));

        var text = ObservationJson.of(observation);

        assertThat(text)
                .contains("\"stalled\":true")
                .contains("confirmedEventIds")
                .contains("cascadedEventIds")
                .contains("\"scores\"")
                .contains("\"weaverChainLength\":5")
                .contains("\"maxCascadedParadoxes\":4")
                .doesNotContain("leads")
                .doesNotContain("chainLength\"")
                .doesNotContain("collapseLimit");
        assertThat(ObservationJson.parse(text)).isEqualTo(observation);
    }

    @Test
    void anObservationWithBoardFactsReadsBackEqual() {
        var base = PolicyFixtures.actionRound(Faction.WEAVERS);
        var banded = new EventView(
                PolicyFixtures.EVENT_A,
                List.of(new OutcomeView(PolicyFixtures.A_HIGH, 50, null, new PublicBand(Band.HIGH, 2))));
        var board = new Board(
                List.of(new Board.PublicDeclaration(
                        PolicyFixtures.id(0x01), DeclarationMode.RALLY, PolicyFixtures.EVENT_A, PolicyFixtures.A_HIGH)),
                new Board.RoundSummary(
                        1,
                        List.of(new Board.ActionSummary(
                                PolicyFixtures.id(0x02),
                                CardCategory.PROBABILITY_SHIFTER,
                                Board.ActionFamily.CARD,
                                false))),
                List.of(new Board.OwnSubmission(
                        WindowKind.ACTION_ROUND,
                        1,
                        null,
                        null,
                        SpecialAction.THREAD,
                        PolicyFixtures.EVENT_A,
                        PolicyFixtures.A_HIGH,
                        null,
                        List.of(),
                        List.of())),
                new Board.ChainView(Board.ChainStatus.ACTIVE, 2),
                List.of(new Board.OpenParadox(
                        Board.ParadoxKind.CHAIN_CONFLICT, PolicyFixtures.EVENT_A, List.of(PolicyFixtures.A_HIGH))));
        var observation = new EntitledObservation(
                0, Faction.WEAVERS, List.of(banded), base.otherPlayerIds(), base.window(), board);

        var text = ObservationJson.of(observation);

        assertThat(ObservationJson.parse(text)).isEqualTo(observation);
        assertThat(text).contains("\"board\"").contains("\"band\"");
    }

    @Test
    void aRetainedObservationReadsBackAsTheSameWindow() {
        var observation = PolicyFixtures.handSelection(Faction.WEAVERS);

        assertThat(ObservationJson.parse(ObservationJson.of(observation))).isEqualTo(observation);
        assertThat(ObservationJson.toMap(observation)).containsKey("window");
    }
}
