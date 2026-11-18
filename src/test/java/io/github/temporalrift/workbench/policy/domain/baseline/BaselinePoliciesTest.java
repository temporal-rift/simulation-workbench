package io.github.temporalrift.workbench.policy.domain.baseline;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import io.github.temporalrift.workbench.policy.PolicyFixtures;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.observation.Board;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.TableRules;
import io.github.temporalrift.workbench.shared.game.Faction;

class BaselinePoliciesTest {

    static Stream<org.junit.jupiter.params.provider.Arguments> everyBundleFactionAndWindow() {
        return BaselinePolicies.all().stream()
                .flatMap(bundle -> Arrays.stream(Faction.values())
                        .flatMap(faction -> PolicyFixtures.allWindows(faction).stream()
                                .map(observation -> org.junit.jupiter.params.provider.Arguments.of(
                                        bundle.id(), bundle, observation))));
    }

    @ParameterizedTest(name = "{0} / {2}")
    @MethodSource("everyBundleFactionAndWindow")
    void everyWindowYieldsALegalCandidateChoice(String id, PolicyBundle bundle, EntitledObservation observation) {
        var decision = bundle.policy().decide(observation, Set.of(), entropy(observation));

        assertThat(decision).isInstanceOf(PolicyDecision.Chosen.class);
        assertThat(CandidateGenerator.generate(observation)).contains(((PolicyDecision.Chosen) decision).candidate());
    }

    @ParameterizedTest(name = "{0} / {2}")
    @MethodSource("everyBundleFactionAndWindow")
    void equalObservationAndSeedGiveTheSameChoiceTwice(
            String id, PolicyBundle bundle, EntitledObservation observation) {
        var first = bundle.policy().decide(observation, Set.of(), entropy(observation));
        var second = bundle.policy().decide(observation, Set.of(), entropy(observation));

        assertThat(second).isEqualTo(first);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bundles")
    void emptyParadoxOfferPasses(PolicyBundle bundle) {
        var observation = PolicyFixtures.paradox(Faction.PROPHETS, true);

        var decision = bundle.policy().decide(observation, Set.of(), entropy(observation));

        assertThat(decision).isEqualTo(new PolicyDecision.Chosen(new Candidate.PassParadox()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bundles")
    void excludingEveryCandidateExhaustsThePolicy(PolicyBundle bundle) {
        var observation = PolicyFixtures.terminal(Faction.ERASERS);

        var decision = bundle.policy().decide(observation, Set.of(new Candidate.ConfirmReady()), entropy(observation));

        assertThat(decision).isInstanceOf(PolicyDecision.Exhausted.class);
        assertThat(PolicyDecision.Exhausted.CODE).isEqualTo("POLICY_EXHAUSTED");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("bundles")
    void observationShapeHasNoObserverOpponentOrControlData(PolicyBundle bundle) {
        var forbidden = List.of("observer", "raw", "event", "credential", "token", "control", "checkpoint", "clock");
        var components = Stream.of(EntitledObservation.class.getRecordComponents())
                .map(RecordComponent::getName)
                .map(String::toLowerCase)
                .toList();

        assertThat(components)
                .containsExactlyInAnyOrder(
                        "seatindex", "faction", "events", "otherplayerids", "window", "board", "rules");
        // The table rules are the announced paradox rules every player knows; nothing hidden.
        assertThat(Stream.of(TableRules.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList())
                .containsExactlyInAnyOrder(
                        "detonateContestsStabilize",
                        "stabilizeDelta",
                        "detonateDelta",
                        "cascadePenalty",
                        "tapestryAbsorbsChainConflict");
        var boardFacts = Stream.of(Board.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
        assertThat(boardFacts)
                .containsExactlyInAnyOrder(
                        "declarations", "lastRoundSummary", "ownSubmissions", "chain", "openParadoxes");
        assertThat(components.stream().filter(name -> forbidden.stream().anyMatch(name::equals)))
                .isEmpty();
    }

    @Test
    void bundlesAreVersionedAndDigestTheirDefinition() {
        assertThat(BaselinePolicies.RANDOM_V1.version()).isEqualTo("1.0.0");
        assertThat(BaselinePolicies.RANDOM_V1.artifactDigest())
                .isEqualTo(sha256("random|1.0.0|" + RandomPolicy.DEFINITION));
        assertThat(BaselinePolicies.FACTION_GREEDY_V1.artifactDigest())
                .isEqualTo(sha256("faction-greedy|1.0.0|" + FactionPreferences.canonicalDefinition()));
        assertThat(BaselinePolicies.RANDOM_V1.artifactDigest())
                .isNotEqualTo(BaselinePolicies.FACTION_GREEDY_V1.artifactDigest());
        assertThat(BaselinePolicies.find("random", "1.0.0")).contains(BaselinePolicies.RANDOM_V1);
        assertThat(BaselinePolicies.find("random", "2.0.0")).isEmpty();
        assertThat(BaselinePolicies.FACTION_GREEDY_V2.version()).isEqualTo("2.0.0");
        assertThat(BaselinePolicies.FACTION_GREEDY_V2.artifactDigest())
                .isEqualTo(sha256("faction-greedy|2.0.0|" + FactionGreedyV2Policy.DEFINITION));
        assertThat(BaselinePolicies.find("faction-greedy", "2.0.0")).contains(BaselinePolicies.FACTION_GREEDY_V2);
    }

    @Test
    void theServedDigestsOfTheVersionOneBundlesAreUnchanged() {
        assertThat(BaselinePolicies.FACTION_GREEDY_V1.artifactDigest())
                .isEqualTo("7078aebf5a3afa7d06de5e891588a06be85c68f8239393d06fadad827149e2bb");
        assertThat(BaselinePolicies.RANDOM_V1.artifactDigest())
                .isEqualTo("b264b46280ba0a4827ab868ea97b9214fa1a8764c116d1f0d3107a5d8a7476dd");
    }

    @ParameterizedTest(name = "{0}")
    @org.junit.jupiter.params.provider.EnumSource(Faction.class)
    void boardFactsDoNotMoveAVersionOneGreedyChoice(Faction faction) {
        var plain = PolicyFixtures.actionRound(faction);
        var withBoard = new EntitledObservation(
                plain.seatIndex(),
                plain.faction(),
                plain.events(),
                plain.otherPlayerIds(),
                plain.window(),
                new Board(
                        List.of(new Board.PublicDeclaration(
                                PolicyFixtures.id(0x01),
                                io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode.RALLY,
                                PolicyFixtures.EVENT_A,
                                PolicyFixtures.A_LOW)),
                        null,
                        List.of(),
                        new Board.ChainView(Board.ChainStatus.ACTIVE, 2),
                        List.of()));

        var policy = BaselinePolicies.FACTION_GREEDY_V1.policy();

        assertThat(policy.decide(withBoard, Set.of(), entropy(withBoard)))
                .isEqualTo(policy.decide(plain, Set.of(), entropy(plain)));
    }

    @Test
    void handSelectionKeepsExactlyFiveDistinctDealtCards() {
        var observation = PolicyFixtures.handSelection(Faction.WEAVERS);
        var deal = ((DecisionWindow.HandSelection) observation.window()).deal();

        var decision = BaselinePolicies.RANDOM_V1.policy().decide(observation, Set.of(), entropy(observation));

        var keep = (Candidate.KeepHand) ((PolicyDecision.Chosen) decision).candidate();
        assertThat(keep.cardInstanceIds()).hasSize(5).doesNotHaveDuplicates();
        assertThat(deal.stream().map(DealtCard::cardInstanceId)).containsAll(keep.cardInstanceIds());
    }

    static Stream<PolicyBundle> bundles() {
        return BaselinePolicies.all().stream();
    }

    private static PolicyEntropy entropy(EntitledObservation observation) {
        return PolicyEntropy.derive(
                7, observation.seatIndex(), observation.window().ref(), 0);
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
