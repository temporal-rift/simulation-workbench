package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.temporalrift.workbench.analysis.domain.scoring.BetRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.FactionScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModel;
import io.github.temporalrift.workbench.shared.game.Faction;

/** One value of a scoring model that a sweep can move, named by the path the request uses. */
final class SweepTarget {

    private static final String FACTION_GROUP = "faction";
    private static final String FACTION_PATH = "factions\\.(?<" + FACTION_GROUP + ">[A-Z_]+)";
    private static final Pattern WIN_THRESHOLD = Pattern.compile("winThreshold");
    private static final Pattern WIN_POINTS = Pattern.compile(FACTION_PATH + "\\.winPoints\\[(?<index>\\d+)]");
    private static final Pattern MISS_POINTS = Pattern.compile(FACTION_PATH + "\\.missPoints");
    private static final Pattern OBJECTIVE_BONUS = Pattern.compile(FACTION_PATH + "\\.objectiveBonus");
    private static final Pattern WIN_RATE =
            Pattern.compile("rates\\.(?<count>\\d)\\.(?<" + FACTION_GROUP + ">[A-Z_]+)\\.winRate");

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
        if (WIN_THRESHOLD.matcher(path).matches()) {
            return winThreshold(path);
        }
        var winPoints = WIN_POINTS.matcher(path);
        if (winPoints.matches()) {
            return winPoints(path, winPoints, model);
        }
        var missPoints = MISS_POINTS.matcher(path);
        if (missPoints.matches()) {
            return factionValue(
                    path,
                    missPoints,
                    model,
                    faction -> true,
                    (current, delta) -> new FactionScoring(
                            current.faction(),
                            current.winPoints(),
                            current.missPoints() + whole(delta),
                            current.resetOn(),
                            current.objectiveWins(),
                            current.objectiveBonus()));
        }
        var bonus = OBJECTIVE_BONUS.matcher(path);
        if (bonus.matches()) {
            return factionValue(
                    path,
                    bonus,
                    model,
                    faction -> faction.objectiveWins() != null,
                    (current, delta) -> new FactionScoring(
                            current.faction(),
                            current.winPoints(),
                            current.missPoints(),
                            current.resetOn(),
                            current.objectiveWins(),
                            current.objectiveBonus() + whole(delta)));
        }
        var rate = WIN_RATE.matcher(path);
        return rate.matches() ? winRate(path, rate, model) : null;
    }

    private static SweepTarget winThreshold(String path) {
        return new SweepTarget(
                path,
                true,
                (base, delta) -> withEngine(
                        base,
                        new ScoringEngine(
                                base.engine().winThreshold() + whole(delta),
                                base.engine().maxEras(),
                                base.engine().factions())));
    }

    private static SweepTarget winPoints(String path, Matcher matcher, ScoringModel model) {
        var faction = faction(matcher.group(FACTION_GROUP), model);
        var index = number(matcher.group("index"));
        if (faction == null || index < 0 || index >= faction.winPoints().size()) {
            return null;
        }
        return new SweepTarget(
                path,
                true,
                (base, delta) -> withFaction(base, faction.faction(), current -> {
                    var points = new ArrayList<>(current.winPoints());
                    points.set(index, points.get(index) + whole(delta));
                    return new FactionScoring(
                            current.faction(),
                            points,
                            current.missPoints(),
                            current.resetOn(),
                            current.objectiveWins(),
                            current.objectiveBonus());
                }));
    }

    /** A whole-number value of one faction's engine, when the faction exists and the value applies to it. */
    private static SweepTarget factionValue(
            String path, Matcher matcher, ScoringModel model, Predicate<FactionScoring> applies, FactionMove change) {
        var faction = faction(matcher.group(FACTION_GROUP), model);
        if (faction == null || !applies.test(faction)) {
            return null;
        }
        return new SweepTarget(
                path,
                true,
                (base, delta) -> withFaction(base, faction.faction(), current -> change.apply(current, delta)));
    }

    private static SweepTarget winRate(String path, Matcher matcher, ScoringModel model) {
        var target = factionNamed(matcher.group(FACTION_GROUP));
        var count = number(matcher.group("count"));
        if (target == null || count < 0) {
            return null;
        }
        var exists = model.rates().stream().anyMatch(item -> item.playerCount() == count && item.faction() == target);
        return exists ? new SweepTarget(path, false, (base, delta) -> withRate(base, count, target, delta)) : null;
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

    private static FactionScoring faction(String name, ScoringModel model) {
        return model.engine().factions().stream()
                .filter(item -> item.faction().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static ScoringModel withEngine(ScoringModel base, ScoringEngine engine) {
        return new ScoringModel(engine, base.playerCounts(), base.rates(), base.gamesPerPlayerCount(), base.seed());
    }

    private static ScoringModel withFaction(ScoringModel base, Faction faction, UnaryOperator<FactionScoring> change) {
        var factions = base.engine().factions().stream()
                .map(item -> item.faction() == faction ? change.apply(item) : item)
                .toList();
        return withEngine(
                base,
                new ScoringEngine(base.engine().winThreshold(), base.engine().maxEras(), factions));
    }

    private static ScoringModel withRate(ScoringModel base, int count, Faction faction, double delta) {
        List<BetRates> rates = base.rates().stream()
                .map(item -> item.playerCount() == count && item.faction() == faction
                        ? new BetRates(
                                item.playerCount(),
                                item.faction(),
                                item.winRate() + delta,
                                item.missRate(),
                                item.extraPointsPerEra())
                        : item)
                .toList();
        return new ScoringModel(base.engine(), base.playerCounts(), rates, base.gamesPerPlayerCount(), base.seed());
    }

    @FunctionalInterface
    private interface Move {
        ScoringModel apply(ScoringModel base, double delta);
    }

    @FunctionalInterface
    private interface FactionMove {
        FactionScoring apply(FactionScoring current, double delta);
    }
}
