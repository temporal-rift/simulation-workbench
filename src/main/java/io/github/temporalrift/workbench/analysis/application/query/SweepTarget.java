package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

import io.github.temporalrift.workbench.analysis.domain.scoring.BetRates;
import io.github.temporalrift.workbench.analysis.domain.scoring.FactionScoring;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringModel;
import io.github.temporalrift.workbench.shared.game.Faction;

/** One value of a scoring model that a sweep can move, named by the path the request uses. */
final class SweepTarget {

    private static final String FACTION = "(?<faction>[A-Z_]+)";
    private static final Pattern WIN_THRESHOLD = Pattern.compile("winThreshold");
    private static final Pattern WIN_POINTS =
            Pattern.compile("factions\\." + FACTION + "\\.winPoints\\[(?<index>\\d+)]");
    private static final Pattern MISS_POINTS = Pattern.compile("factions\\." + FACTION + "\\.missPoints");
    private static final Pattern OBJECTIVE_BONUS = Pattern.compile("factions\\." + FACTION + "\\.objectiveBonus");
    private static final Pattern WIN_RATE = Pattern.compile("rates\\.(?<count>\\d)\\." + FACTION + "\\.winRate");

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
        var winPoints = WIN_POINTS.matcher(path);
        if (winPoints.matches()) {
            var faction = faction(winPoints.group("faction"), model);
            var index = Integer.parseInt(winPoints.group("index"));
            if (faction == null || index >= faction.winPoints().size()) {
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
        var missPoints = MISS_POINTS.matcher(path);
        if (missPoints.matches()) {
            var faction = faction(missPoints.group("faction"), model);
            return faction == null
                    ? null
                    : new SweepTarget(
                            path,
                            true,
                            (base, delta) -> withFaction(
                                    base,
                                    faction.faction(),
                                    current -> new FactionScoring(
                                            current.faction(),
                                            current.winPoints(),
                                            current.missPoints() + whole(delta),
                                            current.resetOn(),
                                            current.objectiveWins(),
                                            current.objectiveBonus())));
        }
        var bonus = OBJECTIVE_BONUS.matcher(path);
        if (bonus.matches()) {
            var faction = faction(bonus.group("faction"), model);
            return faction == null || faction.objectiveWins() == null
                    ? null
                    : new SweepTarget(
                            path,
                            true,
                            (base, delta) -> withFaction(
                                    base,
                                    faction.faction(),
                                    current -> new FactionScoring(
                                            current.faction(),
                                            current.winPoints(),
                                            current.missPoints(),
                                            current.resetOn(),
                                            current.objectiveWins(),
                                            current.objectiveBonus() + whole(delta))));
        }
        var rate = WIN_RATE.matcher(path);
        if (rate.matches()) {
            var target = Faction.valueOf(rate.group("faction"));
            var count = Integer.parseInt(rate.group("count"));
            var exists =
                    model.rates().stream().anyMatch(item -> item.playerCount() == count && item.faction() == target);
            return exists ? new SweepTarget(path, false, (base, delta) -> withRate(base, count, target, delta)) : null;
        }
        return null;
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
}
