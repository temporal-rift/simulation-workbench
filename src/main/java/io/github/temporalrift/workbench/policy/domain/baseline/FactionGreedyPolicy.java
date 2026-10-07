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
            case Candidate.KeepHand keep ->
                keep.cardInstanceIds().stream()
                        .mapToLong(id -> (long) FactionPreferences.card(
                                                faction, cards.get(id).type())
                                        .affinity()
                                * AFFINITY_SCALE)
                        .sum();
            case Candidate.Declare declare ->
                DECLARATION_SCORE
                        + (declare.mode() == DeclarationMode.MOMENTUM ? MOMENTUM_BONUS : 0)
                        + targetScore(
                                Bias.LEADING,
                                new Target.EventOutcome(declare.eventId(), declare.outcomeId()),
                                outcomes);
            case Candidate.PlayCard play -> {
                var preference = FactionPreferences.card(
                        faction, cards.get(play.cardInstanceId()).type());
                yield preference.affinity() * (long) AFFINITY_SCALE
                        + targetScore(preference.bias(), play.target(), outcomes);
            }
            case Candidate.PlayParadoxCard play -> {
                var preference = FactionPreferences.card(
                        faction, cards.get(play.cardInstanceId()).type());
                yield preference.affinity() * (long) AFFINITY_SCALE
                        + targetScore(preference.bias(), play.target(), outcomes);
            }
            case Candidate.PlaySpecial play -> {
                var preference = FactionPreferences.special(faction, play.action());
                yield preference.affinity() * (long) AFFINITY_SCALE
                        + targetScore(preference.bias(), play.target(), outcomes);
            }
            case Candidate.Decline decline -> 0;
            case Candidate.Pass pass -> 0;
            case Candidate.PassParadox pass -> 0;
            case Candidate.ConfirmReady ready -> 0;
        };
    }

    private static long targetScore(Bias bias, Target target, Map<UUID, OutcomeView> outcomes) {
        if (bias == Bias.NEUTRAL) {
            return 0;
        }
        return switch (target) {
            case Target.EventOutcome single -> lean(bias, weight(outcomes, single.outcomeId()));
            case Target.OutcomePair pair ->
                (lean(bias, weight(outcomes, pair.targetOutcomeId()))
                                + lean(flip(bias), weight(outcomes, pair.sourceOutcomeId())))
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
                window.cards().stream().map(playable -> playable.card()).toList();
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
