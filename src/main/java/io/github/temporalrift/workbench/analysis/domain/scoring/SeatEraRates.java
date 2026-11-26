package io.github.temporalrift.workbench.analysis.domain.scoring;

import java.util.List;

import io.github.temporalrift.workbench.shared.game.Faction;

/**
 * What one faction's seat-era is like at one player count: the chance that its bet settles with each bet reason, the
 * chance that a placed bet records none, and the mean occurrences of every other reason. The rest is
 * {@code NOT_PLACED}.
 */
public record SeatEraRates(
        int playerCount,
        Faction faction,
        List<BetReasonRate> betReasons,
        double voidedRate,
        List<OtherReason> otherReasons) {

    public SeatEraRates {
        betReasons = List.copyOf(betReasons);
        otherReasons = List.copyOf(otherReasons);
    }

    /** The chance per seat-era that the bet settles with {@code scoreReason}. */
    public record BetReasonRate(String scoreReason, double rate) {}

    /** The mean occurrences per seat-era of a reason that settles no bet. */
    public record OtherReason(String scoreReason, double perEra) {}
}
