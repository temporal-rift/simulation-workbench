package io.github.temporalrift.workbench.analysis.domain.scoring;

/** The running score of one seat under its faction's engine. */
public final class SeatScore {

    private final FactionScoring engine;
    private double total;
    private int winRun;
    private int wins;

    public SeatScore(FactionScoring engine) {
        this.engine = engine;
    }

    /** Scores one era's bet outcome and returns the total afterwards. */
    public double apply(BetOutcome outcome) {
        switch (outcome) {
            case WON -> win();
            case MISSED -> total += engine.missPoints();
            default -> {
                // Neither a win nor a miss scores.
            }
        }
        if (engine.resetOn().contains(outcome)) {
            winRun = 0;
        }
        return total;
    }

    /** Adds the seat's other scores of the era. */
    public void addExtra(double points) {
        total += points;
    }

    public double total() {
        return total;
    }

    private void win() {
        winRun++;
        wins++;
        var points = engine.winPoints();
        total += points.get(Math.min(winRun, points.size()) - 1);
        if (engine.objectiveWins() != null && wins == engine.objectiveWins()) {
            total += engine.objectiveBonus();
        }
    }
}
