package io.github.temporalrift.workbench.search.application.command;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import io.github.temporalrift.workbench.search.domain.candidate.Candidate;
import io.github.temporalrift.workbench.search.domain.port.out.CampaignRepository;
import io.github.temporalrift.workbench.search.domain.round.RoundResult;
import io.github.temporalrift.workbench.search.domain.wave.Game;
import io.github.temporalrift.workbench.search.domain.wave.Wave;

/** What a campaign has recorded so far, read once for a step: its candidates, waves, games and decided rounds. */
record Ledger(List<Candidate> candidates, List<Wave> waves, List<Game> games, List<RoundResult> rounds, long logSize) {

    static Ledger read(CampaignRepository campaigns, UUID campaignId) {
        return new Ledger(
                campaigns.candidates(campaignId),
                campaigns.waves(campaignId),
                campaigns.games(campaignId),
                campaigns.rounds(campaignId),
                campaigns.log(campaignId, 1, 0).total());
    }

    int nextSequence() {
        return (int) logSize + 1;
    }

    List<Candidate> generatedIn(int round) {
        return candidates.stream()
                .filter(candidate -> candidate.round() == round)
                .toList();
    }

    /** The candidates that survived the round before {@code round}, best first; none for round 0. */
    List<Candidate> survivorsBefore(int round) {
        if (round == 0) {
            return List.of();
        }
        var byId = new HashMap<String, Candidate>();
        candidates.forEach(candidate -> byId.put(candidate.id(), candidate));
        return rounds.get(round - 1).survivorIds().stream().map(byId::get).toList();
    }

    /** The candidates that play {@code round}: the survivors of the round before and the ones it generated. */
    List<Candidate> inPlay(int round) {
        return java.util.stream.Stream.concat(survivorsBefore(round).stream(), generatedIn(round).stream())
                .sorted(Comparator.comparing(Candidate::id))
                .toList();
    }

    Map<String, Set<String>> seedsByCandidate() {
        var seeds = new HashMap<String, Set<String>>();
        games.forEach(game ->
                seeds.computeIfAbsent(game.candidateId(), id -> new HashSet<>()).add(game.seed()));
        return seeds;
    }

    List<Wave> wavesOf(int round) {
        return waves.stream().filter(wave -> wave.round() == round).toList();
    }

    Wave wave(int index) {
        return waves.get(index);
    }
}
