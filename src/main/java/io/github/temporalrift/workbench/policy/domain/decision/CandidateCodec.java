package io.github.temporalrift.workbench.policy.domain.decision;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import io.github.temporalrift.workbench.policy.domain.observation.CardCategory;
import io.github.temporalrift.workbench.policy.domain.observation.DeclarationMode;
import io.github.temporalrift.workbench.policy.domain.observation.SpecialAction;

/**
 * The canonical text of a candidate. It is what the decision transcript retains, so a saved case can be
 * executed again from the transcript alone, and what the semantic digest hashes.
 */
public final class CandidateCodec {

    private static final String SEPARATOR = ":";
    private static final String LIST_SEPARATOR = ",";
    private static final String NO_TARGET = "-";

    private CandidateCodec() {}

    public static String encode(Candidate candidate) {
        return switch (candidate) {
            case Candidate.KeepHand keep -> "keep" + SEPARATOR + ids(keep.cardInstanceIds());
            case Candidate.Declare declare ->
                join("declare", declare.mode().name(), declare.eventId(), declare.outcomeId());
            case Candidate.Decline _ -> "decline";
            case Candidate.PlayCard card -> join("card", card.cardInstanceId(), encode(card.target()));
            case Candidate.PlaySpecial special ->
                join("special", special.action().name(), encode(special.target()));
            case Candidate.Pass _ -> "pass";
            case Candidate.PlayParadoxCard paradox ->
                join("paradox-card", paradox.cardInstanceId(), encode(paradox.target()));
            case Candidate.PassParadox _ -> "paradox-pass";
            case Candidate.ConfirmReady _ -> "ready";
        };
    }

    /**
     * @throws IllegalArgumentException when the text is not a candidate written by {@link #encode}
     */
    public static Candidate decode(String text) {
        var parts = text.split(SEPARATOR, 3);
        try {
            return switch (parts[0]) {
                case "keep" -> new Candidate.KeepHand(uuids(parts[1]));
                case "declare" -> declare(text);
                case "decline" -> new Candidate.Decline();
                case "card" -> new Candidate.PlayCard(UUID.fromString(parts[1]), decodeTarget(parts[2]));
                case "special" -> new Candidate.PlaySpecial(SpecialAction.valueOf(parts[1]), decodeTarget(parts[2]));
                case "pass" -> new Candidate.Pass();
                case "paradox-card" ->
                    new Candidate.PlayParadoxCard(
                            UUID.fromString(parts[1]), (Target.EventOutcome) decodeTarget(parts[2]));
                case "paradox-pass" -> new Candidate.PassParadox();
                case "ready" -> new Candidate.ConfirmReady();
                default -> throw new IllegalArgumentException("Unknown candidate kind");
            };
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Not a retained candidate: " + text, e);
        }
    }

    private static Candidate declare(String text) {
        var parts = text.split(SEPARATOR);
        return new Candidate.Declare(
                DeclarationMode.valueOf(parts[1]), UUID.fromString(parts[2]), UUID.fromString(parts[3]));
    }

    private static String encode(Target target) {
        return switch (target) {
            case null -> NO_TARGET;
            case Target.Disguise disguise ->
                join("disguise", disguise.category().name());
            case Target.EventOutcome outcome -> join("outcome", outcome.eventId(), outcome.outcomeId());
            case Target.OutcomePair pair ->
                join("pair", pair.eventId(), pair.sourceOutcomeId(), pair.targetOutcomeId());
            case Target.Events events -> "events" + SEPARATOR + ids(events.eventIds());
            case Target.Player player -> join("player", player.playerId());
            case Target.Players players -> "players" + SEPARATOR + ids(players.playerIds());
        };
    }

    private static Target decodeTarget(String text) {
        if (NO_TARGET.equals(text)) {
            return null;
        }
        var parts = text.split(SEPARATOR);
        return switch (parts[0]) {
            case "disguise" -> new Target.Disguise(CardCategory.valueOf(parts[1]));
            case "outcome" -> new Target.EventOutcome(UUID.fromString(parts[1]), UUID.fromString(parts[2]));
            case "pair" ->
                new Target.OutcomePair(UUID.fromString(parts[1]), UUID.fromString(parts[2]), UUID.fromString(parts[3]));
            case "events" -> new Target.Events(uuids(parts[1]));
            case "player" -> new Target.Player(UUID.fromString(parts[1]));
            case "players" -> new Target.Players(uuids(parts[1]));
            default -> throw new IllegalArgumentException("Unknown target kind");
        };
    }

    private static String join(Object... parts) {
        return String.join(SEPARATOR, Arrays.stream(parts).map(Object::toString).toList());
    }

    private static String ids(List<UUID> ids) {
        return String.join(LIST_SEPARATOR, ids.stream().map(UUID::toString).toList());
    }

    private static List<UUID> uuids(String text) {
        return text.isEmpty()
                ? List.of()
                : Arrays.stream(text.split(LIST_SEPARATOR))
                        .map(UUID::fromString)
                        .toList();
    }
}
