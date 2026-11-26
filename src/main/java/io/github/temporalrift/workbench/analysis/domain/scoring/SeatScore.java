package io.github.temporalrift.workbench.analysis.domain.scoring;

/**
 * The running score of one seat under its faction's engine, kept in exact millionths of a point so that equal
 * scores reached in different orders stay equal and a threshold is crossed exactly when the arithmetic says so.
 */
public final class SeatScore {

    /** Millionths in one point. */
    public static final long UNITS = 1_000_000L;

    private final FactionScoring engine;
    private long total;
    private int winRun;
    private int wins;

    public SeatScore(FactionScoring engine) {
        this.engine = engine;
    }

    /** Scores one era's bet outcome and returns the total afterwards, in millionths. */
    public long apply(BetOutcome outcome) {
        switch (outcome) {
            case WON -> win();
            case MISSED -> total += engine.missPoints() * UNITS;
            default -> {
                // Neither a win nor a miss scores.
            }
        }
        if (engine.resetOn().contains(outcome)) {
            winRun = 0;
        }
        return total;
    }

    /** Adds the seat's other scores of the era, in millionths. */
    public void addExtra(long units) {
        total += units;
    }

    /** Resolves a decimal number of points to millionths. */
    public static long toUnits(double points) {
        return Math.round(points * UNITS);
    }

    /** The total in millionths. */
    public long total() {
        return total;
    }

    private void win() {
        winRun++;
        wins++;
        var points = engine.winPoints();
        total += points.get(Math.min(winRun, points.size()) - 1) * UNITS;
        if (engine.objectiveWins() != null && wins == engine.objectiveWins()) {
            total += engine.objectiveBonus() * UNITS;
        }
    }
}
