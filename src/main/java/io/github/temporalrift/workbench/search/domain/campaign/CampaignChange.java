package io.github.temporalrift.workbench.search.domain.campaign;

import java.time.Instant;
import java.util.List;

import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.log.LogEntry;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;

/**
 * One step of a campaign as a single write: the campaign's next value and everything the step records with it. It
 * applies only if the campaign is still at the version the step started from, and then all of it applies together.
 *
 * @param waves the waves the step starts
 * @param games the games those waves play
 * @param log the decisions the step takes
 * @param round the round the step decides, or null
 * @param command the key of the command that asks for the change, claimed with it; null for a step of the worker
 */
public record CampaignChange(
        Campaign next,
        Instant accruedAt,
        long expectedVersion,
        List<Candidate> candidates,
        List<Wave> waves,
        List<Game> games,
        List<LogEntry> log,
        RoundResult round,
        CampaignCommandKey command) {

    public CampaignChange {
        candidates = List.copyOf(candidates);
        waves = List.copyOf(waves);
        games = List.copyOf(games);
        log = List.copyOf(log);
    }

    /** A step that only changes the campaign. */
    public static CampaignChange of(StoredCampaign from, Campaign next, Instant accruedAt) {
        return new CampaignChange(
                next, accruedAt, from.version(), List.of(), List.of(), List.of(), List.of(), null, null);
    }

    public CampaignChange recording(List<Candidate> generated, List<LogEntry> entries) {
        return new CampaignChange(next, accruedAt, expectedVersion, generated, waves, games, entries, round, command);
    }

    public CampaignChange playing(Wave wave, List<Game> played) {
        return new CampaignChange(
                next, accruedAt, expectedVersion, candidates, List.of(wave), played, log, round, command);
    }

    public CampaignChange deciding(RoundResult decided, List<LogEntry> entries) {
        return new CampaignChange(
                next, accruedAt, expectedVersion, candidates, waves, games, entries, decided, command);
    }

    public CampaignChange claiming(CampaignCommandKey key) {
        return new CampaignChange(next, accruedAt, expectedVersion, candidates, waves, games, log, round, key);
    }
}
