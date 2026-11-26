package io.github.temporalrift.workbench.analysis.application.query;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.temporalrift.workbench.analysis.domain.scoring.Escalation;
import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModel;
import io.github.temporalrift.workbench.analysis.domain.scoring.SeatEraRates;
import io.github.temporalrift.workbench.shared.game.Faction;

/** One value of a scoring model that a sweep can move, named by the path the request uses. */
final class SweepTarget {

    private static final String REASON = "reason";
    private static final String RATE_PATH = "rates\\.(?<count>\\d)\\.(?<faction>[A-Z_]+)\\.";
    private static final Pattern MAX_ERAS = Pattern.compile("maxEras");
    private static final Pattern REASON_POINTS = Pattern.compile("reasonPoints\\.(?<" + REASON + ">.{1,64})");
    private static final Pattern ESCALATION =
            Pattern.compile("escalations\\.(?<name>[a-z][a-z0-9-]{0,31})\\.(?<field>base|increment)");
    private static final Pattern BET_REASON = Pattern.compile(RATE_PATH + "betReasons\\.(?<" + REASON + ">.{1,64})");
    private static final Pattern VOIDED_RATE = Pattern.compile(RATE_PATH + "voidedRate");
    private static final Pattern OTHER_REASON =
            Pattern.compile(RATE_PATH + "otherReasons\\.(?<" + REASON + ">.{1,64})");

    private final String path;
    private final boolean whole;
    private final Move move;

    private SweepTarget(String path, boolean whole, Move move) {
        this.path = path;
        this.whole = whole;
        this.move = move;
    }

    /** Resolves a sweep path, or returns {@code null} when it names no sweepable value of the model. */
    static SweepTarget resolve(String path, ScoringModel model) {
        if (MAX_ERAS.matcher(path).matches()) {
            return new SweepTarget(
                    path,
                    true,
                    (base, delta) -> withEngine(
                            base,
                            new ScoringEngine(
                                    base.engine().maxEras() + whole(delta),
                                    base.engine().reasonPoints(),
                                    base.engine().escalations())));
        }
        var points = REASON_POINTS.matcher(path);
        if (points.matches()) {
            return reasonPoints(path, points.group(REASON), model);
        }
        var escalation = ESCALATION.matcher(path);
        if (escalation.matches()) {
            return escalation(path, escalation, model);
        }
        var bet = BET_REASON.matcher(path);
        if (bet.matches()) {
            return betReason(path, bet, model);
        }
        var voided = VOIDED_RATE.matcher(path);
        if (voided.matches()) {
            return rate(
                    path,
                    voided,
                    model,
                    (item, delta) -> new SeatEraRates(
                            item.playerCount(),
                            item.faction(),
                            item.betReasons(),
                            item.voidedRate() + delta,
                            item.otherReasons()));
        }
        var other = OTHER_REASON.matcher(path);
        return other.matches() ? otherReason(path, other, model) : null;
    }

    private static SweepTarget reasonPoints(String path, String reason, ScoringModel model) {
        if (model.engine().reasonPoints().stream()
                .noneMatch(item -> item.scoreReason().equals(reason))) {
            return null;
        }
        return new SweepTarget(
                path,
                true,
                (base, delta) -> withEngine(
                        base,
                        new ScoringEngine(
                                base.engine().maxEras(),
                                base.engine().reasonPoints().stream()
                                        .map(item -> item.scoreReason().equals(reason)
                                                ? new ReasonPoints(reason, item.points() + whole(delta))
                                                : item)
                                        .toList(),
                                base.engine().escalations())));
    }

    private static SweepTarget escalation(String path, Matcher matcher, ScoringModel model) {
        var name = matcher.group("name");
        var base = "base".equals(matcher.group("field"));
        if (model.engine().escalations().stream().noneMatch(item -> item.name().equals(name))) {
            return null;
        }
        return new SweepTarget(
                path,
                true,
                (current, delta) -> withEngine(
                        current,
                        new ScoringEngine(
                                current.engine().maxEras(),
                                current.engine().reasonPoints(),
                                current.engine().escalations().stream()
                                        .map(item -> item.name().equals(name) ? moved(item, base, whole(delta)) : item)
                                        .toList())));
    }

    private static Escalation moved(Escalation item, boolean base, int delta) {
        return new Escalation(
                item.name(),
                item.scoreReasons(),
                item.base() + (base ? delta : 0),
                item.increment() + (base ? 0 : delta),
                item.resetOn());
    }

    private static SweepTarget betReason(String path, Matcher matcher, ScoringModel model) {
        var reason = matcher.group(REASON);
        var exists = model.rates().stream()
                .filter(item -> matches(item, matcher))
                .anyMatch(item -> item.betReasons().stream()
                        .anyMatch(rate -> rate.scoreReason().equals(reason)));
        if (!exists) {
            return null;
        }
        return rate(
                path,
                matcher,
                model,
                (item, delta) -> new SeatEraRates(
                        item.playerCount(),
                        item.faction(),
                        item.betReasons().stream()
                                .map(rate -> rate.scoreReason().equals(reason)
                                        ? new SeatEraRates.BetReasonRate(reason, rate.rate() + delta)
                                        : rate)
                                .toList(),
                        item.voidedRate(),
                        item.otherReasons()));
    }

    private static SweepTarget otherReason(String path, Matcher matcher, ScoringModel model) {
        var reason = matcher.group(REASON);
        var exists = model.rates().stream()
                .filter(item -> matches(item, matcher))
                .anyMatch(item -> item.otherReasons().stream()
                        .anyMatch(other -> other.scoreReason().equals(reason)));
        if (!exists) {
            return null;
        }
        return rate(
                path,
                matcher,
                model,
                (item, delta) -> new SeatEraRates(
                        item.playerCount(),
                        item.faction(),
                        item.betReasons(),
                        item.voidedRate(),
                        item.otherReasons().stream()
                                .map(other -> other.scoreReason().equals(reason)
                                        ? new SeatEraRates.OtherReason(reason, other.perEra() + delta)
                                        : other)
                                .toList()));
    }

    /** A rate of one player count and faction, when the request holds a rates item for them. */
    private static SweepTarget rate(String path, Matcher matcher, ScoringModel model, RateMove change) {
        var faction = factionNamed(matcher.group("faction"));
        var count = number(matcher.group("count"));
        if (faction == null || count < 0) {
            return null;
        }
        if (model.rates().stream().noneMatch(item -> item.playerCount() == count && item.faction() == faction)) {
            return null;
        }
        return new SweepTarget(
                path,
                false,
                (base, delta) -> withRates(
                        base,
                        base.rates().stream()
                                .map(item -> item.playerCount() == count && item.faction() == faction
                                        ? change.apply(item, delta)
                                        : item)
                                .toList()));
    }

    private static boolean matches(SeatEraRates item, Matcher matcher) {
        return item.playerCount() == number(matcher.group("count"))
                && item.faction().name().equals(matcher.group("faction"));
    }

    /** Parses a digit run, answering -1 when it does not fit an int. */
    private static int number(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException _) {
            return -1;
        }
    }

    private static Faction factionNamed(String name) {
        try {
            return Faction.valueOf(name);
        } catch (IllegalArgumentException _) {
            return null;
        }
    }

    String path() {
        return path;
    }

    /** Whether the value is an integer, so a step must be one too. */
    boolean whole() {
        return whole;
    }

    ScoringModel moved(ScoringModel base, double delta) {
        return move.apply(base, delta);
    }

    private static int whole(double delta) {
        return (int) delta;
    }

    private static ScoringModel withEngine(ScoringModel base, ScoringEngine engine) {
        return new ScoringModel(
                engine, base.factions(), base.playerCounts(), base.rates(), base.gamesPerPlayerCount(), base.seed());
    }

    private static ScoringModel withRates(ScoringModel base, List<SeatEraRates> rates) {
        return new ScoringModel(
                base.engine(), base.factions(), base.playerCounts(), rates, base.gamesPerPlayerCount(), base.seed());
    }

    @FunctionalInterface
    private interface Move {
        ScoringModel apply(ScoringModel base, double delta);
    }

    @FunctionalInterface
    private interface RateMove {
        SeatEraRates apply(SeatEraRates item, double delta);
    }
}
