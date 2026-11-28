package io.github.temporalrift.workbench.analysis.application.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

import io.github.temporalrift.workbench.analysis.domain.scoring.Escalation;
import io.github.temporalrift.workbench.analysis.domain.scoring.ReasonPoints;
import io.github.temporalrift.workbench.analysis.domain.scoring.ScoringEngine;

/** Resolves the requested one-point moves before reading case data. */
final class RescoreSweep {
    private static final Pattern REASON = Pattern.compile("reasonPoints\\.(.{1,64})");
    private static final Pattern ESCALATION =
            Pattern.compile("escalations\\.([a-z][a-z0-9-]{0,31})\\.(base|increment)");

    record Move(String parameter, int direction, ScoringEngine engine) {}

    static List<Move> resolve(ScoringEngine engine, List<String> paths) {
        if (paths == null) {
            return List.of();
        }
        if (paths.isEmpty() || paths.size() > 24) {
            throw ScoringModelValidator.invalid("sweep", "sweep must contain between 1 and 24 parameters");
        }
        var seen = new HashSet<String>();
        var moves = new ArrayList<Move>();
        for (int index = 0; index < paths.size(); index++) {
            var path = paths.get(index);
            if (path == null || !seen.add(path)) {
                throw ScoringModelValidator.invalid("sweep[" + index + "]", "parameter is null or repeated");
            }
            moves.addAll(resolvePath(engine, path, index));
        }
        return List.copyOf(moves);
    }

    private static List<Move> resolvePath(ScoringEngine engine, String path, int index) {
        var reason = REASON.matcher(path);
        if (reason.matches()) {
            var item = engine.reasonPoints().stream()
                    .filter(p -> p.scoreReason().equals(reason.group(1)))
                    .findFirst();
            if (item.isPresent()) {
                return reasonMoves(engine, path, item.orElseThrow());
            }
        }
        var escalation = ESCALATION.matcher(path);
        if (escalation.matches()) {
            var item = engine.escalations().stream()
                    .filter(e -> e.name().equals(escalation.group(1)))
                    .findFirst();
            if (item.isPresent()) {
                return escalationMoves(
                        engine, path, item.orElseThrow(), escalation.group(2).equals("base"));
            }
        }
        throw ScoringModelValidator.invalid("sweep[" + index + "]", "unknown or malformed parameter");
    }

    private static List<Move> reasonMoves(ScoringEngine engine, String path, ReasonPoints item) {
        var moves = new ArrayList<Move>();
        for (int direction : new int[] {-1, 1}) {
            ScoringEngine neighbor = null;
            if (Math.abs(item.points() + direction) <= ScoringModelValidator.MAX_POINTS) {
                var points = engine.reasonPoints().stream()
                        .map(p -> p.scoreReason().equals(item.scoreReason())
                                ? new ReasonPoints(p.scoreReason(), p.points() + direction)
                                : p)
                        .toList();
                neighbor = new ScoringEngine(engine.maxEras(), points, engine.escalations());
            }
            moves.add(new Move(path, direction, neighbor));
        }
        return moves;
    }

    private static List<Move> escalationMoves(ScoringEngine engine, String path, Escalation item, boolean base) {
        int value = base ? item.base() : item.increment();
        int limit = base ? ScoringModelValidator.MAX_POINTS : ScoringModelValidator.MAX_INCREMENT;
        var moves = new ArrayList<Move>();
        for (int direction : new int[] {-1, 1}) {
            ScoringEngine neighbor = null;
            if (Math.abs(value + direction) <= limit) {
                var escalations = engine.escalations().stream()
                        .map(e -> e.name().equals(item.name()) ? movedEscalation(e, base, direction) : e)
                        .toList();
                neighbor = new ScoringEngine(engine.maxEras(), engine.reasonPoints(), escalations);
            }
            moves.add(new Move(path, direction, neighbor));
        }
        return moves;
    }

    private static Escalation movedEscalation(Escalation item, boolean base, int direction) {
        return new Escalation(
                item.name(),
                item.scoreReasons(),
                item.base() + (base ? direction : 0),
                item.increment() + (base ? 0 : direction),
                item.resetOn());
    }

    private RescoreSweep() {}
}
