package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
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
import io.github.temporalrift.workbench.policy.domain.observation.TargetShape;
import io.github.temporalrift.workbench.shared.game.CardType;
import io.github.temporalrift.workbench.shared.game.Faction;
import io.github.temporalrift.workbench.shared.game.SpecialAction;
import io.github.temporalrift.workbench.shared.window.WindowKind;

class FactionRecipePolicyTest {

    private static final UUID EVENT_A = PolicyFixtures.EVENT_A;
    private static final UUID EVENT_B = PolicyFixtures.EVENT_B;
    private static final UUID EVENT_C = PolicyFixtures.EVENT_C;
    private static final UUID A3 = PolicyFixtures.id(0xA3);
    private static final UUID B3 = PolicyFixtures.id(0xB3);
    private static final UUID C3 = PolicyFixtures.id(0xC3);
    private static final UUID SHIFTER = PolicyFixtures.id(0x02);
    private static final UUID QUIET = PolicyFixtures.id(0x03);
    private static final UUID DECLARER = PolicyFixtures.id(0x01);
    private static final UUID SELF = PolicyFixtures.id(0x09);

    private final FactionRecipePolicy policy = new FactionRecipePolicy();

    @Test
    void aProphetWritesOneEventPerEra() {
        var board = board(List.of(special(1, SpecialAction.FORESIGHT, EVENT_A, A3)), null, null, List.of());

        var chosen = choose(observation(Faction.PROPHETS, 2, specials(Faction.PROPHETS), List.of(), board, bands()));

        assertThat(chosen)
                .satisfies(choice -> assertThat(choice instanceof Candidate.PlaySpecial play
                                && play.action() == SpecialAction.FORESIGHT)
                        .isFalse());
    }

    @Test
    void aProphetThatHasNotWrittenYetPlaysForesight() {
        var chosen = choose(observation(
                Faction.PROPHETS,
                1,
                specials(Faction.PROPHETS),
                List.of(),
                board(List.of(), null, null, List.of()),
                bands()));

        assertThat(chosen)
                .isInstanceOfSatisfying(
                        Candidate.PlaySpecial.class,
                        play -> assertThat(play.action()).isEqualTo(SpecialAction.FORESIGHT));
    }

    @Test
    void aWeaverDoesNotRepeatAnAcceptedThread() {
        var board = board(List.of(special(1, SpecialAction.THREAD, EVENT_A, A3)), null, null, List.of());

        var chosen = choose(observation(Faction.WEAVERS, 2, specials(Faction.WEAVERS), List.of(), board, bands()));

        assertThat(chosen).isNotInstanceOf(Candidate.PlaySpecial.class);
    }

    @Test
    void aWeaverProtectsAChainOfTwoLinks() {
        var board = board(
                List.of(special(1, SpecialAction.THREAD, EVENT_A, A3)),
                null,
                new Board.ChainView(Board.ChainStatus.ACTIVE, 2),
                List.of());

        var chosen =
                choose(observation(Faction.WEAVERS, 2, specials(Faction.WEAVERS), List.of(push(1)), board, bands()));

        assertThat(chosen)
                .isEqualTo(new Candidate.PlaySpecial(SpecialAction.TAPESTRY, new Target.EventOutcome(EVENT_A, A3)));
    }

    @Test
    void aWeaverReaimsALinkWhoseOutcomeFellLow() {
        var board = board(List.of(special(1, SpecialAction.THREAD, EVENT_A, A3)), null, null, List.of());
        var bands = Map.of(A3, Band.LOW, B3, Band.HIGH, C3, Band.MEDIUM);

        var chosen = choose(observation(Faction.WEAVERS, 3, specials(Faction.WEAVERS), List.of(push(1)), board, bands));

        assertThat(chosen)
                .isEqualTo(new Candidate.PlaySpecial(SpecialAction.REWEAVE, new Target.EventOutcome(EVENT_B, B3)));
    }

    @Test
    void aProphetBetsOnAStrongWrittenOutcome() {
        var board = board(List.of(special(1, SpecialAction.FORESIGHT, EVENT_A, A3)), null, null, List.of());

        var chosen = choose(observation(
                Faction.PROPHETS, 3, specials(Faction.PROPHETS), List.of(push(1)), board, Map.of(A3, Band.HIGH)));

        assertThat(chosen)
                .isEqualTo(new Candidate.PlaySpecial(SpecialAction.FULFILLMENT, new Target.EventOutcome(EVENT_A, A3)));
    }

    @Test
    void anActivistExposesAParticipantSeenPlayingAShifter() {
        var summary = summary(1, SHIFTER, QUIET);

        var chosen = choose(observation(
                Faction.ACTIVISTS,
                2,
                specials(Faction.ACTIVISTS),
                List.of(push(1)),
                board(List.of(), summary, null, List.of()),
                bands()));

        assertThat(chosen).isEqualTo(new Candidate.PlaySpecial(SpecialAction.EXPOSE, new Target.Player(SHIFTER)));
    }

    @Test
    void anEraserCorruptsOnceItsAnnihilateIsSpent() {
        var summary = summary(2, SHIFTER, QUIET);
        var spent = List.of(
                new PlayableSpecial(SpecialAction.CORRUPT, TargetShape.PLAYER),
                new PlayableSpecial(SpecialAction.CASCADE, TargetShape.EVENT_OUTCOME));

        var chosen = choose(observation(
                Faction.ERASERS, 3, spent, List.of(suppress(1)), board(List.of(), summary, null, List.of()), bands()));

        assertThat(chosen).isEqualTo(new Candidate.PlaySpecial(SpecialAction.CORRUPT, new Target.Player(SHIFTER)));
    }

    @Test
    void aRevisionistMimicsItsOwnRewriteOutcome() {
        var board = board(List.of(special(1, SpecialAction.REWRITE, EVENT_B, B3)), null, null, List.of());

        var chosen = choose(
                observation(Faction.REVISIONISTS, 2, specials(Faction.REVISIONISTS), List.of(push(1)), board, bands()));

        assertThat(chosen)
                .isEqualTo(new Candidate.PlaySpecial(SpecialAction.MIMIC, new Target.EventOutcome(EVENT_B, B3)));
    }

    @Test
    void anotherParticipantsDeclarationIsNotFed() {
        var bands = Map.of(A3, Band.HIGH);
        var towardA3 = new Candidate.PlayCard(push(1).card().cardInstanceId(), new Target.EventOutcome(EVENT_A, A3));
        var declaration = new Board.PublicDeclaration(DECLARER, DeclarationMode.RALLY, EVENT_A, A3);

        var undeclared = choose(observation(
                Faction.PROPHETS, 2, List.of(), List.of(push(1)), board(List.of(), null, null, List.of()), bands));
        var declared = choose(observation(
                Faction.PROPHETS,
                2,
                List.of(),
                List.of(push(1)),
                board(List.of(), null, null, List.of(declaration)),
                bands));

        assertThat(undeclared).isEqualTo(towardA3);
        assertThat(declared).isNotEqualTo(towardA3);
    }

    @Test
    void aPushAvoidsALowOutcomeWhenAnotherIsOpen() {
        var bands = Map.of(A3, Band.LOW, B3, Band.HIGH, C3, Band.LOW);

        var chosen = choose(observation(
                Faction.ACTIVISTS, 3, List.of(), List.of(push(1)), board(List.of(), null, null, List.of()), bands));

        assertThat(chosen)
                .isEqualTo(
                        new Candidate.PlayCard(push(1).card().cardInstanceId(), new Target.EventOutcome(EVENT_B, B3)));
    }

    @Test
    void anActivistBacksItsOwnDeclaredOutcome() {
        // As the projection records it: the own declaration submission carries no mode or target; the public
        // declaration by the seat (a player that is not among the others) does.
        var submission = new Board.OwnSubmission(
                WindowKind.DECLARATION, null, null, null, null, null, null, null, List.of(), List.of());
        var declaration = new Board.PublicDeclaration(SELF, DeclarationMode.RALLY, EVENT_B, PolicyFixtures.id(0xB1));

        var chosen = choose(observation(
                Faction.ACTIVISTS,
                3,
                List.of(),
                List.of(push(1)),
                board(List.of(submission), null, null, List.of(declaration)),
                bands()));

        assertThat(chosen)
                .isEqualTo(new Candidate.PlayCard(
                        push(1).card().cardInstanceId(), new Target.EventOutcome(EVENT_B, PolicyFixtures.id(0xB1))));
    }

    @Test
    void aRoundOneSummaryOfThePreviousEraNamesNoExposeOrCorruptTarget() {
        var summary = summary(3, SHIFTER, QUIET);
        var board = board(List.of(), summary, null, List.of());

        var chosen = choose(observation(
                Faction.ERASERS,
                1,
                List.of(new PlayableSpecial(SpecialAction.CORRUPT, TargetShape.PLAYER)),
                List.of(),
                board,
                bands()));

        assertThat(chosen).isEqualTo(new Candidate.Pass());
    }

    @Test
    void aRefusedSpecialIsNotRetriedOnTheSameEventInTheSameWindow() {
        var observation =
                observation(Faction.ERASERS, 1, specials(Faction.ERASERS), List.of(push(1)), Board.EMPTY, bands());
        var refused = new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, new Target.EventOutcome(EVENT_A, A3));

        var chosen = chooseExcluding(observation, Set.of(refused));

        assertThat(chosen).isInstanceOfSatisfying(Candidate.PlaySpecial.class, play -> {
            assertThat(play.action()).isEqualTo(SpecialAction.ANNIHILATE);
            assertThat(((Target.EventOutcome) play.target()).eventId()).isNotEqualTo(EVENT_A);
        });
    }

    @Test
    void aSpecialRefusedOnEveryEventIsNotTriedAgain() {
        var observation =
                observation(Faction.ERASERS, 1, specials(Faction.ERASERS), List.of(push(1)), Board.EMPTY, bands());
        var refused = Set.<Candidate>of(
                new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, new Target.EventOutcome(EVENT_A, A3)),
                new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, new Target.EventOutcome(EVENT_B, B3)),
                new Candidate.PlaySpecial(SpecialAction.ANNIHILATE, new Target.EventOutcome(EVENT_C, C3)));

        var chosen = chooseExcluding(observation, refused);

        assertThat(chosen instanceof Candidate.PlaySpecial special && special.action() == SpecialAction.ANNIHILATE)
                .as("chose %s", chosen)
                .isFalse();
    }

    @Test
    void onlyWastedCandidatesLeftExhaustTheWindow() {
        var observation = observation(Faction.ERASERS, 3, specials(Faction.ERASERS), List.of(), Board.EMPTY, bands());
        var excluded = CandidateGenerator.generate(observation).stream()
                .filter(candidate -> !FactionRecipePolicy.wasted(candidate, BoardReading.of(observation)))
                .collect(Collectors.toSet());

        var decision = policy.decide(
                observation,
                excluded,
                PolicyEntropy.derive(
                        7, observation.seatIndex(), observation.window().ref(), excluded.size()));

        assertThat(CandidateGenerator.generate(observation)).hasSizeGreaterThan(excluded.size());
        assertThat(decision).isInstanceOf(PolicyDecision.Exhausted.class);
    }

    private Candidate chooseExcluding(EntitledObservation observation, Set<Candidate> excluded) {
        var decision = policy.decide(
                observation,
                excluded,
                PolicyEntropy.derive(
                        7, observation.seatIndex(), observation.window().ref(), excluded.size()));
        return ((PolicyDecision.Chosen) decision).candidate();
    }

    @Test
    void obscureIsNotPlayedInRoundThree() {
        var board = board(List.of(special(1, SpecialAction.REWRITE, EVENT_B, B3)), null, null, List.of());
        var obscureOnly = List.of(new PlayableSpecial(SpecialAction.OBSCURE, TargetShape.EVENT_OUTCOME));

        assertThat(choose(observation(Faction.REVISIONISTS, 3, obscureOnly, List.of(), board, bands())))
                .isEqualTo(new Candidate.Pass());
        assertThat(choose(observation(Faction.REVISIONISTS, 2, obscureOnly, List.of(), board, bands())))
                .isInstanceOf(Candidate.PlaySpecial.class);
    }

    @Test
    void aWeaverWhoseProtectedLinkWasErasedThreadsAgainInsteadOfReweaving() {
        var board = board(
                List.of(special(1, SpecialAction.THREAD, EVENT_A, A3), special(2, SpecialAction.TAPESTRY, EVENT_A, A3)),
                null,
                new Board.ChainView(Board.ChainStatus.ACTIVE, 2),
                List.of());
        var bands = Map.of(A3, Band.LOW, B3, Band.HIGH, C3, Band.MEDIUM);

        var chosen = choose(observation(Faction.WEAVERS, 3, specials(Faction.WEAVERS), List.of(), board, bands));

        assertThat(chosen)
                .isEqualTo(new Candidate.PlaySpecial(SpecialAction.THREAD, new Target.EventOutcome(EVENT_B, B3)));
    }

    private Candidate choose(EntitledObservation observation) {
        var decision = policy.decide(
                observation,
                Set.of(),
                PolicyEntropy.derive(
                        7, observation.seatIndex(), observation.window().ref(), 0));
        return ((PolicyDecision.Chosen) decision).candidate();
    }

    private static EntitledObservation observation(
            Faction faction,
            int round,
            List<PlayableSpecial> specials,
            List<PlayableCard> cards,
            Board board,
            Map<UUID, Band> bands) {
        var hand = cards.stream().map(card -> new HandCard(card.card(), true)).toList();
        var window = new DecisionWindow.ActionRound(1, round, hand, cards, specials);
        return new EntitledObservation(0, faction, events(bands), PolicyFixtures.players(), window, board);
    }

    private static List<EventView> events(Map<UUID, Band> bands) {
        var events = new ArrayList<EventView>();
        for (var event : PolicyFixtures.events()) {
            events.add(new EventView(
                    event.eventId(),
                    event.outcomes().stream()
                            .map(outcome -> new OutcomeView(
                                    outcome.outcomeId(),
                                    outcome.printedWeight(),
                                    null,
                                    bands.containsKey(outcome.outcomeId())
                                            ? new PublicBand(bands.get(outcome.outcomeId()), 2)
                                            : null))
                            .toList()));
        }
        return events;
    }

    private static Map<UUID, Band> bands() {
        return Map.of();
    }

    private static Board board(
            List<Board.OwnSubmission> own,
            Board.RoundSummary summary,
            Board.ChainView chain,
            List<Board.PublicDeclaration> declarations) {
        return new Board(declarations, summary, own, chain, List.of());
    }

    private static Board.OwnSubmission special(int round, SpecialAction action, UUID eventId, UUID outcomeId) {
        return new Board.OwnSubmission(
                WindowKind.ACTION_ROUND, round, null, null, action, eventId, outcomeId, null, List.of(), List.of());
    }

    private static Board.RoundSummary summary(int round, UUID shifter, UUID quiet) {
        return new Board.RoundSummary(
                round,
                List.of(
                        new Board.ActionSummary(
                                shifter, CardCategory.PROBABILITY_SHIFTER, Board.ActionFamily.CARD, false),
                        new Board.ActionSummary(quiet, CardCategory.INFORMATION, Board.ActionFamily.CARD, false)));
    }

    private static List<PlayableSpecial> specials(Faction faction) {
        return switch (faction) {
            case ERASERS ->
                List.of(
                        new PlayableSpecial(SpecialAction.ANNIHILATE, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.CORRUPT, TargetShape.PLAYER),
                        new PlayableSpecial(SpecialAction.CASCADE, TargetShape.EVENT_OUTCOME));
            case PROPHETS ->
                List.of(
                        new PlayableSpecial(SpecialAction.FORESIGHT, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.SEAL, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.FULFILLMENT, TargetShape.EVENT_OUTCOME));
            case REVISIONISTS ->
                List.of(
                        new PlayableSpecial(SpecialAction.REWRITE, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.MIMIC, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.OBSCURE, TargetShape.EVENT_OUTCOME));
            case WEAVERS ->
                List.of(
                        new PlayableSpecial(SpecialAction.THREAD, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.TAPESTRY, TargetShape.EVENT_OUTCOME),
                        new PlayableSpecial(SpecialAction.REWEAVE, TargetShape.EVENT_OUTCOME));
            case ACTIVISTS -> List.of(new PlayableSpecial(SpecialAction.EXPOSE, TargetShape.PLAYER));
        };
    }

    private static PlayableCard push(int n) {
        return new PlayableCard(PolicyFixtures.card(n, CardType.PUSH), TargetShape.EVENT_OUTCOME, 1);
    }

    private static PlayableCard suppress(int n) {
        return new PlayableCard(PolicyFixtures.card(n, CardType.SUPPRESS), TargetShape.EVENT_OUTCOME, 1);
    }
}
