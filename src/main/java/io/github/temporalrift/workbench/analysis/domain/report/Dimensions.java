package io.github.temporalrift.workbench.analysis.domain.report;

import io.github.temporalrift.workbench.analysis.domain.game.CardKey;
import io.github.temporalrift.workbench.analysis.domain.game.EndReason;
import io.github.temporalrift.workbench.analysis.domain.game.Faction;
import io.github.temporalrift.workbench.analysis.domain.game.ParadoxType;
import io.github.temporalrift.workbench.analysis.domain.game.SpecialAction;

/** What a metric is restricted to; a null dimension means the metric is not split by it. */
public record Dimensions(
        Faction faction,
        CardKey card,
        SpecialAction specialAction,
        ParadoxType paradoxType,
        EndReason endReason,
        Double quantile) {

    public static final Dimensions NONE = new Dimensions(null, null, null, null, null, null);

    public static Dimensions of(Faction faction) {
        return new Dimensions(faction, null, null, null, null, null);
    }

    public static Dimensions of(CardKey card) {
        return new Dimensions(null, card, null, null, null, null);
    }

    public static Dimensions of(SpecialAction specialAction) {
        return new Dimensions(null, null, specialAction, null, null, null);
    }

    public static Dimensions of(ParadoxType paradoxType) {
        return new Dimensions(null, null, null, paradoxType, null, null);
    }

    public static Dimensions of(EndReason endReason) {
        return new Dimensions(null, null, null, null, endReason, null);
    }

    public Dimensions at(double quantileValue) {
        return new Dimensions(faction, card, specialAction, paradoxType, endReason, quantileValue);
    }
}
