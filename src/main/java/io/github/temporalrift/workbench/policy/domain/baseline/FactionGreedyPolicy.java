package io.github.temporalrift.workbench.policy.domain.baseline;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.github.temporalrift.workbench.policy.domain.baseline.FactionPreferences.Bias;
import io.github.temporalrift.workbench.policy.domain.decision.BotPolicy;
import io.github.temporalrift.workbench.policy.domain.decision.Candidate;
import io.github.temporalrift.workbench.policy.domain.decision.CandidateGenerator;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyDecision;
import io.github.temporalrift.workbench.policy.domain.decision.PolicyEntropy;
import io.github.temporalrift.workbench.policy.domain.decision.Target;
import io.github.temporalrift.workbench.policy.domain.observation.DealtCard;
import io.github.temporalrift.workbench.policy.domain.observation.DecisionWindow;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.EntitledObservation;
import io.github.temporalrift.workbench.policy.domain.observation.EventView;
import io.github.temporalrift.workbench.policy.domain.observation.OutcomeView;
import io.github.temporalrift.workbench.policy.domain.observation.PlayableCard;

/**
 * {@code faction-greedy-v1}: scores every candidate from the caller's faction preferences and the
 * outcome weights it legitimately knows, then draws among the top-scored candidates with seeded
 * entropy. It never models authoritative resolution or scoring.
 */
final class FactionGreedyPolicy implements BotPolicy {

    private static final int AFFINITY_SCALE = 1000;
    private static final int DECLARATION_SCORE = 8 * AFFINITY_SCALE;
    private static final int MOMENTUM_BONUS = AFFINITY_SCALE / 20;

    @Override
    public PolicyDecision decide(EntitledObservation observation, Set<Candidate> excluded, PolicyEntropy entropy) {
        var available = CandidateGenerator.generate(observation).stream()
                .filter(candidate -> !excluded.contains(candidate))
                .toList();
        if (available.isEmpty()) {
            return new PolicyDecision.Exhausted();
        }
        var cards = cardsById(observation);
        var outcomes = outcomesById(observation);
        var best = Long.MIN_VALUE;
        var top = new ArrayList<Candidate>();
        for (var candidate : available) {
            var score = score(candidate, observation, cards, outcomes);
            if (score > best) {
                best = score;
                top.clear();
            }
            if (score == best) {
                top.add(candidate);
            }
        }
        return new PolicyDecision.Chosen(top.get(entropy.nextIndex(top.size())));
    }

    private static long score(
            Candidate candidate,
            EntitledObservation observation,
            Map<UUID, DealtCard> cards,
            Map<UUID, OutcomeView> outcomes) {
        var faction = observation.faction();
        return switch (candidate) {
            case Candidate.KeepHand(var cardInstanceIds) ->
                cardInstanceIds.stream()
                        .mapToLong(id -> (long) FactionPreferences.card(
                                                faction, cards.get(id).type())
                                        .affinity()
                                * AFFINITY_SCALE)
                        .sum();
            case Candidate.Declare(var mode, var eventId, var outcomeId) ->
                DECLARATION_SCORE
                        + (mode == DeclarationMode.MOMENTUM ? MOMENTUM_BONUS : 0)
                        + targetScore(Bias.LEADING, new Target.EventOutcome(eventId, outcomeId), outcomes);
            case Candidate.PlayCard(var cardInstanceId, var target) ->
                weighted(
                        FactionPreferences.card(
                                faction, cards.get(cardInstanceId).type()),
                        target,
                        outcomes);
            case Candidate.PlayParadoxCard(var cardInstanceId, var target) ->
                weighted(
                        FactionPreferences.card(
                                faction, cards.get(cardInstanceId).type()),
                        target,
                        outcomes);
            case Candidate.PlaySpecial(var action, var target) ->
                weighted(FactionPreferences.special(faction, action), target, outcomes);
            case Candidate.Decline _, Candidate.Pass _, Candidate.PassParadox _, Candidate.ConfirmReady _ -> 0;
        };
    }

    private static long weighted(
            FactionPreferences.Preference preference, Target target, Map<UUID, OutcomeView> outcomes) {
        return preference.affinity() * (long) AFFINITY_SCALE + targetScore(preference.bias(), target, outcomes);
    }

    private static long targetScore(Bias bias, Target target, Map<UUID, OutcomeView> outcomes) {
        if (bias == Bias.NEUTRAL) {
            return 0;
        }
        return switch (target) {
            case Target.EventOutcome(var _, var outcomeId) -> lean(bias, weight(outcomes, outcomeId));
            case Target.OutcomePair(var _, var sourceOutcomeId, var targetOutcomeId) ->
                (lean(bias, weight(outcomes, targetOutcomeId)) + lean(flip(bias), weight(outcomes, sourceOutcomeId)))
                        / 2;
            default -> 0;
        };
    }

    private static int lean(Bias bias, int weight) {
        return bias == Bias.LEADING ? weight : 100 - weight;
    }

    private static Bias flip(Bias bias) {
        return bias == Bias.LEADING ? Bias.TRAILING : Bias.LEADING;
    }

    private static int weight(Map<UUID, OutcomeView> outcomes, UUID outcomeId) {
        return outcomes.get(outcomeId).knownWeight();
    }

    private static Map<UUID, DealtCard> cardsById(EntitledObservation observation) {
        List<DealtCard> cards = switch (observation.window()) {
            case DecisionWindow.HandSelection window -> window.deal();
            case DecisionWindow.ActionRound window ->
                window.cards().stream().map(PlayableCard::card).toList();
            case DecisionWindow.ParadoxResolution window -> window.offer();
            default -> List.of();
        };
        return cards.stream().collect(Collectors.toMap(DealtCard::cardInstanceId, Function.identity()));
    }

    private static Map<UUID, OutcomeView> outcomesById(EntitledObservation observation) {
        return observation.events().stream()
                .map(EventView::outcomes)
                .flatMap(List::stream)
                .collect(Collectors.toMap(OutcomeView::outcomeId, Function.identity(), (first, second) -> first));
    }
}
